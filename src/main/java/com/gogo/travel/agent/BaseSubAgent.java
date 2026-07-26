package com.gogo.travel.agent;

import com.gogo.travel.agent.context.AgentSessionContext;
import com.gogo.travel.agent.context.AgentSessionContextHolder;
import com.gogo.travel.agent.hook.*;
import com.gogo.travel.agent.memory.TravelPreferenceLongTermMemoryFactory;
import com.gogo.travel.agent.tools.*;
import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.tool.ToolExecutionContext;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.util.List;

/**
 * 子智能体抽象基类，集中沉淀所有子类复用的成员变量与工具方法。
 *
 * @author Hollis
 */

public abstract class BaseSubAgent {

    /**
     * 子 Agent 工具执行超时（分钟）
     */
    protected static final int TOOL_TIMEOUT_MINUTES = 1;
    /**
     * 子 Agent 工具最大尝试次数（含首次）
     */
    protected static final int TOOL_MAX_ATTEMPTS = 3;
    /**
     * 子 Agent 工具初始退避时间（秒）
     */
    protected static final int TOOL_INITIAL_BACKOFF_SECONDS = 3;

    @Autowired
    protected AgentExecutionLoggerHook executionLoggerHook;

    @Autowired
    protected ProgressNotifierHook progressNotifierHook;

    @Autowired
    protected SessionPersistenceHook sessionPersistenceHook;

    @Autowired
    protected ActiveAgentPersistenceHook activeAgentPersistenceHook;

    @Autowired
    protected TravelPreferenceLongTermMemoryFactory memoryFactory;

    @Autowired
    protected PolicyTools policyTools;

    @Autowired
    protected TravelOrderReadTools travelOrderReadTools;

    @Autowired
    protected BookingReadTools bookingReadTools;

    @Autowired
    protected InfoQueryTools infoQueryTools;

    @Autowired
    protected ApplicationContext applicationContext;

    @Autowired
    protected AgentSkillRepository skillRepository;

    @Autowired
    protected QueryUserInfoTools queryUserInfoTools;

    @Autowired
    protected DestinationLiveTools destinationLiveTools;

    @Autowired(required = false)
    @Qualifier("weatherMcpClient")
    @Nullable
    protected McpClientWrapper weatherMcpClient;

    @Autowired
    @Qualifier("stableModel")
    protected Model stableModel;

    @Autowired
    @Qualifier("strongModel")
    protected Model strongModel;

    @Autowired
    @Qualifier("strongModelWithThinking")
    protected Model strongModelWithThinking;

    /**
     * 天气 MCP 工具白名单（从 {@code app.weather-mcp.enabled-tools} 读取）。
     * Spring 自动按逗号分隔解析为 {@code List<String>}。
     */
    @Value("${app.weather-mcp.enabled-tools:}")
    protected List<String> weatherMcpEnabledTools;

    @Autowired(required = false)
    @Qualifier("oriznVisaMcpClient")
    @Nullable
    protected McpClientWrapper oriznVisaMcpClient;

    /**
     * Orizn Visa MCP 工具白名单（从 {@code app.orizn-mcp.enabled-tools} 读取）。
     */
    @Value("${app.orizn-mcp.enabled-tools:}")
    protected List<String> oriznMcpEnabledTools;

    @Autowired
    protected ToolCircuitBreakerHook toolCircuitBreakerHook;

    /**
     * MCP/CLI 工具结果去重 Hook：在结果回填 memory 前删除与 content 重复的 structuredContent。
     */
    @Autowired
    protected CliResultCompressHook cliResultCompressHook;

    /**
     * 动态时间注入 Hook：在每轮 LLM 推理前向 inputMessages 中插入包含当前时间的 system 消息，
     * 保持主 system prompt 前缀稳定以利用百炼隐式缓存。
     */
    @Autowired
    protected DynamicTimeInjectionHook dynamicTimeInjectionHook;

    /**
     * 从当前请求线程上下文构造工具执行上下文，供 {@code .toolExecutionContext(...)} 注入。
     * 未设置上下文时返回空上下文。
     */
    public static ToolExecutionContext createToolCtx() {
        AgentSessionContext sessionCtx = AgentSessionContextHolder.get();
        return sessionCtx != null
                ? ToolExecutionContext.builder().register(sessionCtx).build()
                : ToolExecutionContext.empty();
    }

    /**
     * 为当前请求用户构造长期记忆。
     *
     * @param factory 长期记忆工厂；为 {@code null} 时直接返回 {@code null}
     * @return 长期记忆实例；factory 为 {@code null} 或当前线程未携带会话上下文时返回 {@code null}
     */
    protected static LongTermMemory createLongTermMemory(TravelPreferenceLongTermMemoryFactory factory) {
        if (factory == null) {
            return null;
        }
        AgentSessionContext sessionCtx = AgentSessionContextHolder.get();
        String userId = sessionCtx != null ? sessionCtx.getUserId() : null;
        return factory.create(userId);
    }

    /**
     * 子类默认的工具执行配置：最多等 1 分钟、最多 3 次尝试（含首次）、初始退避 3 秒、仅网络错误时重试。
     * 子类如需差异化配置可自行构建 {@link ExecutionConfig}。
     */
    public ExecutionConfig getToolConfig() {
        return ExecutionConfig.builder()
                .timeout(Duration.ofMinutes(TOOL_TIMEOUT_MINUTES))
                .maxAttempts(TOOL_MAX_ATTEMPTS)
                .initialBackoff(Duration.ofSeconds(TOOL_INITIAL_BACKOFF_SECONDS))
                .retryOn(error -> error instanceof java.io.IOException)
                .build();
    }

    public GenerateOptions getEnableThinkingGenerateOptions(Integer thinkingBudget) {
        return GenerateOptions.builder()
                // 开启缓存，框架会自动在每次请求时对 system messages 和 最后一条消息 加上 cache_control （https://bailian.console.aliyun.com/cn-beijing?tab=doc#/doc/?type=model&url=2862577）
                .cacheControl(true)
                // 深度思考模型有时会生成冗长的推理过程，增加等待时间并消耗更多 Token。通过thinking_budget参数可设置推理过程的最大 Token 数，超过限制后模型立即输出回复。
                .thinkingBudget(thinkingBudget)
                .build();
    }

    public GenerateOptions getGenerateOptions() {
        return GenerateOptions.builder()
                // 开启缓存，框架会自动在每次请求时对 system messages 和 最后一条消息 加上 cache_control （https://bailian.console.aliyun.com/cn-beijing?tab=doc#/doc/?type=model&url=2862577）
                .cacheControl(true)
                .build();
    }

}
