package com.gogo.travel.agent;

import com.gogo.travel.agent.common.PromptLoader;
import com.gogo.travel.agent.context.AgentSessionContext;
import com.gogo.travel.agent.context.AgentSessionContextHolder;
import com.gogo.travel.agent.hook.ActiveAgentPersistenceHook;
import com.gogo.travel.agent.hook.AgentExecutionLoggerHook;
import com.gogo.travel.agent.hook.ProgressNotifierHook;
import com.gogo.travel.agent.hook.SessionPersistenceHook;
import com.gogo.travel.agent.memory.AgentMemoryFactory;
import com.gogo.travel.agent.memory.TravelPreferenceLongTermMemoryFactory;
import com.gogo.travel.agent.tools.InfoQueryTools;
import com.gogo.travel.agent.tools.UserInteractionTools;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.hook.PendingToolRecoveryHook;
import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.memory.LongTermMemoryMode;
import io.agentscope.core.memory.autocontext.AutoContextHook;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.ToolExecutionContext;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.subagent.SubAgentConfig;
import io.agentscope.core.tool.subagent.SubAgentProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

import java.time.Duration;
import java.util.List;

/**
 * @author Hollis
 */
@Configuration("masterAgentConfiguration")
public class MasterAgent {

    /** 主 Agent 工具执行超时（分钟） */
    private static final int TOOL_TIMEOUT_MINUTES = 15;
    /** 主 Agent 最大推理迭代次数 */
    private static final int MAX_ITERATIONS = 15;

    @Autowired
    @Qualifier("strongModel")
    private Model strongModel;
    @Autowired
    @Qualifier("stableModel")
    private Model stableModel;
    @Autowired
    private ApplicationContext context;
    @Autowired
    private InfoQueryTools infoQueryTools;
    @Autowired
    private AgentExecutionLoggerHook executionLoggerHook;
    @Autowired
    private ProgressNotifierHook progressNotifierHook;
    @Autowired
    private SessionPersistenceHook sessionPersistenceHook;
    @Autowired
    private ActiveAgentPersistenceHook activeAgentPersistenceHook;
    @Autowired
    private TravelPreferenceLongTermMemoryFactory memoryFactory;

    @Bean(name = "masterAgent")
    @Scope("prototype")
    public ReActAgent build() {
        // 从 TransmittableThreadLocal 读取当前会话上下文
        // TTL 自动传播到 boundedElastic 线程，SubAgentProvider 无需手动重新 set
        AgentSessionContext sessionCtx = AgentSessionContextHolder.get();
        String userId = sessionCtx != null ? sessionCtx.getUserId() : null;
        LongTermMemory longTermMemory =
                memoryFactory != null ? memoryFactory.create(userId) : null;

        ToolExecutionContext masterToolCtx = sessionCtx != null
                ? ToolExecutionContext.builder().register(sessionCtx).build()
                : ToolExecutionContext.empty();

        Toolkit toolkit = new Toolkit();
        toolkit.registration().tool(infoQueryTools).apply();
        // 支持主动向用户提问（Human-in-the-Loop）
        toolkit.registration().tool(new UserInteractionTools()).apply();

        toolkit.registration()
                .subAgent((SubAgentProvider<ReActAgent>) () ->
                                context.getBean("itineraryManageAgent", ReActAgent.class),
                        SubAgentConfig.builder()
                                .toolName("itinerary_manage_agent")
                                .description("行程单全生命周期管理：收集信息、提交审批、查询差旅单/审批状态、取消申请、修改申请。参数：message（任务描述），可选 session_id（继续会话）。")
                                .forwardEvents(false)
                                .build())
                .apply();
        toolkit.registration()
                .subAgent((SubAgentProvider<ReActAgent>) () ->
                                context.getBean("itineraryPlanAgent", ReActAgent.class),
                        SubAgentConfig.builder()
                                .toolName("itinerary_plan_agent")
                                .description("调用 ItineraryPlanAgent 处理审批通过后的行程规划、交通/酒店搜索比价与预订。参数：message（任务描述），可选 session_id（继续会话）。")
                                .forwardEvents(false)
                                .build())
                .apply();
        toolkit.registration()
                .subAgent((SubAgentProvider<ReActAgent>) () ->
                                context.getBean("infoAgent", ReActAgent.class),
                        SubAgentConfig.builder()
                                .toolName("info_agent")
                                .description("调用 InfoAgent 处理差旅政策、目的地景点、签证入境政策及通用公共信息查询。参数：message（任务描述），可选 session_id（继续会话）。")
                                .forwardEvents(false)
                                .build())
                .apply();
        toolkit.registration()
                .subAgent((SubAgentProvider<ReActAgent>) () ->
                                context.getBean("bookingAgent", ReActAgent.class),
                        SubAgentConfig.builder()
                                .toolName("booking_agent")
                                .description("调用 BookingAgent 处理用户确认方案后的预订执行（出票/订房/订火车票）及已有预订的取消。参数：message（含用户确认的方案编号或取消指令），可选 session_id（继续会话）。")
                                .forwardEvents(false)
                                .build())
                .apply();

        ReActAgent agent = ReActAgent.builder()
                .name("MasterAgent")
                .description("智能差旅主智能体，负责协调 ItineraryManageAgent、ItineraryPlanAgent、InfoAgent 完成完整差旅服务")
                .model(strongModel)
                .toolkit(toolkit)
                .toolExecutionContext(masterToolCtx)
                .toolExecutionConfig(ExecutionConfig.builder()
                        .timeout(Duration.ofMinutes(TOOL_TIMEOUT_MINUTES))
                        .maxAttempts(3)
                        .build())
                // 自动压缩/卸载大工具结果与历史，抑制多轮输入 token 膨胀（需搭配 AutoContextHook）
                .memory(AgentMemoryFactory.create(stableModel))
                .longTermMemory(longTermMemory)
                .longTermMemoryMode(LongTermMemoryMode.AGENT_CONTROL)
                .hooks(List.of(new AutoContextHook(), executionLoggerHook, progressNotifierHook, sessionPersistenceHook, activeAgentPersistenceHook,
                        new PendingToolRecoveryHook()))
                .sysPrompt(PromptLoader.load("master-agent-system.md"))
                .maxIters(MAX_ITERATIONS)
                .generateOptions(GenerateOptions.builder()
                        // 开启缓存，框架会自动在每次请求时对 system messages 和 最后一条消息 加上 cache_control （https://bailian.console.aliyun.com/cn-beijing?tab=doc#/doc/?type=model&url=2862577）
                        .cacheControl(true)
                        .build())
                .build();

        return agent;
    }
}
