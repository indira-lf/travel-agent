package com.travel.agent.config;

import com.travel.agent.circuitbreaker.*;
import com.travel.agent.hook.*;
import com.travel.agent.hook.ToolCircuitBreakerHook;
import com.travel.agent.memory.TravelPreferenceLongTermMemoryFactory;
import com.travel.agent.registry.SseEmitterRegistry;
import com.travel.agent.session.ActiveAgentSessionStore;
import io.agentscope.core.session.Session;
import io.agentscope.core.session.mysql.MysqlSession;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Path;

/**
 * @author Hollis
 */
@Configuration
@EnableAsync
public class TravelAgentConfig {

    @Bean
    public AgentSkillRepository fileSystemSkillRepository(
            @Value("${travel.skill.path:classpath:skills}") String skillPath) throws IOException {
        if (skillPath.startsWith("classpath:")) {
            return new ClasspathSkillRepository(skillPath.substring("classpath:".length()));
        }
        return new FileSystemSkillRepository(Path.of(skillPath));
    }

    /**
     * 旅行偏好长期记忆工厂。
     *
     * <p>基于百炼（Bailian）记忆库，按用户维度动态创建长期记忆实例，
     * 供 MasterAgent / ItineraryPlanAgent 在 {@code AGENT_CONTROL} 模式下使用。</p>
     */
    @Bean
    public TravelPreferenceLongTermMemoryFactory travelPreferenceLongTermMemoryFactory(
            @Value("${agentscope.dashscope.api-key:}") String apiKey,
            @Value("${agentscope.bailian.memory-library-id:}") String memoryLibraryId,
            @Value("${agentscope.bailian.project-id:}") String projectId,
            @Value("${agentscope.bailian.profile-schema:}") String profileSchema,
            com.travel.agent.memory.TravelPreferenceMemoryCache travelPreferenceMemoryCache) {
        return new TravelPreferenceLongTermMemoryFactory(apiKey, memoryLibraryId, projectId, profileSchema,
                travelPreferenceMemoryCache);
    }

    @Bean
    public AgentExecutionLoggerHook agentExecutionLoggerHook() {
        return new AgentExecutionLoggerHook();
    }

    @Bean
    public ProgressNotifierHook progressNotifierHook(SseEmitterRegistry emitterRegistry) {
        return new ProgressNotifierHook(emitterRegistry);
    }

    /**
     * AgentScope MySQL 会话存储。
     * 复用项目已有的 DataSource（Druid），自动建表。
     * 表名默认为 {@code agentscope_session}，用于持久化 Agent 多轮对话历史。
     */
    @Bean
    public Session agentSession(DataSource dataSource) {
        return new MysqlSession(dataSource, "travel", "agentscope_session", true);
    }

    /**
     * 多轮对话记忆持久化 Hook。
     * PreCallEvent 时加载历史记忆，PostCallEvent 时保存本轮对话，挂载在所有有状态 Agent 上。
     */
    @Bean
    public SessionPersistenceHook sessionPersistenceHook(Session agentSession) {
        return new SessionPersistenceHook(agentSession);
    }

    /**
     * Redis 消息监听容器，用于跨节点广播 Agent 中断消息。
     */
    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        return container;
    }

    @Bean
    public ActiveAgentPersistenceHook activeAgentPersistenceHook(ActiveAgentSessionStore activeAgentSessionStore) {
        return new ActiveAgentPersistenceHook(activeAgentSessionStore);
    }

    /**
     * 标题生成智能体专用线程池，避免异步标题生成阻塞主对话流程。
     */
    @Bean(name = "titleTaskExecutor")
    public ThreadPoolTaskExecutor titleTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("title-agent-");
        executor.initialize();
        return executor;
    }

    /**
     * Tool 维度熔断状态存储。
     *
     * <p>key 命名格式：{@code {prefix}fail:{toolName}} / {@code {prefix}gen:{toolName}}。
     * 用于 {@link ToolCircuitBreakerHook} 的白名单 tool 熔断监控。</p>
     */
    @Bean("toolFailureStore")
    public CircuitBreakStore toolFailureStore(StringRedisTemplate redisTemplate,
                                              CircuitBreakerProperties properties) {
        return new CircuitBreakStore(redisTemplate, properties);
    }

    /**
     * Tool 维度熔断 Hook。
     *
     * <p>通过 {@link ToolGroup#removeTool(String)} 将 tool 从所在 group 真正卸载，
     * 模型看不到即不会被调用；通过 {@link ToolGroup#addTool(String)} 恢复。</p>
     *
     * <p>所有 tool 维度熔断配置（开关 / 白黑名单 / 阈值 / 冷却 / Redis 键空间等）均走 {@link CircuitBreakerProperties}。</p>
     */
    @Bean
    public ToolCircuitBreakerHook toolCircuitBreakerHook(
            CircuitBreakerProperties properties,
            @Qualifier("toolFailureStore") CircuitBreakStore toolFailureStore) {
        return new ToolCircuitBreakerHook(properties, toolFailureStore);
    }
}
