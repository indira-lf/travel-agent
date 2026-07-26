package com.travel.agent;

import com.travel.agent.common.PromptLoader;
import com.travel.agent.context.AgentSessionContext;
import com.travel.agent.context.AgentSessionContextHolder;
import com.travel.agent.hook.*;
import com.travel.agent.memory.AgentMemoryFactory;
import com.travel.agent.tools.ApiKeyTools;
import com.travel.agent.tools.ItineraryPlannerTool;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.hook.PendingToolRecoveryHook;
import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.memory.LongTermMemoryMode;
import io.agentscope.core.memory.autocontext.AutoContextHook;
import io.agentscope.core.memory.autocontext.AutoContextMemory;
import io.agentscope.core.plan.PlanNotebook;
import io.agentscope.core.skill.SkillBox;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.ToolkitConfig;
import io.agentscope.core.tool.coding.ShellCommandTool;
import io.agentscope.core.tool.subagent.SubAgentConfig;
import io.agentscope.core.tool.subagent.SubAgentProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

import java.util.List;
import java.util.Set;

/**
 * @author Hollis
 */
@Configuration("itineraryPlanAgentConfiguration")
public class ItineraryPlanAgent extends BaseSubAgent {

    /**
     * 行程规划 Agent 最大推理迭代次数
     */
    private static final int MAX_ITERATIONS = 15;

    @Autowired
    protected ApiKeyTools apiKeyTools;

    @Autowired
    protected FlightApiKeyHook flightApiKeyHook;

    @Autowired
    private RghUserIsolationHook rghUserIsolationHook;

    @Autowired
    protected TuniuApiKeyHook tuniuApiKeyHook;

    @Autowired
    protected SkillContentCollapseHook skillContentCollapseHook;

    /**
     * 往返行程规划工具（plan_itinerary）：输入候选 + LLM 偏好分 → 组合/客观分/合成排序 → 存 Redis。
     */
    @Autowired
    protected ItineraryPlannerTool itineraryPlannerTool;

    @Bean(name = "itineraryPlanAgent")
    @Scope("prototype")
    public ReActAgent build() {
        // 开启并行执行
        Toolkit toolkit = new Toolkit(ToolkitConfig.builder()
                .parallel(true)
                .build());
        toolkit.registration().tool(policyTools).apply();
        toolkit.registration().tool(travelOrderReadTools).apply();
        // query_booking_record（查询外部预订记录：机票/酒店/火车票等）
        toolkit.registration().tool(bookingReadTools).apply();
        toolkit.registration().tool(queryUserInfoTools).apply();
        toolkit.registration().tool(apiKeyTools).apply();
        toolkit.registration().tool(destinationLiveTools).apply();
        toolkit.registration().tool(itineraryPlannerTool).apply();
        toolkit.registration().mcpClient(weatherMcpClient).enableTools(weatherMcpEnabledTools).apply();
        toolkit.registration().mcpClient(oriznVisaMcpClient).enableTools(oriznMcpEnabledTools).apply();

        // 行程审核子智能体：委托 ItineraryReviewAgent 完成六维结构化审核（读当前用户 result.json）。
        // 注意：子 agent 作为工具调用受下方 toolExecutionConfig 的超时约束，已放宽到分钟级，
        toolkit.registration()
                .subAgent((SubAgentProvider<ReActAgent>) () ->
                                applicationContext.getBean("itineraryReviewAgent", ReActAgent.class),
                        SubAgentConfig.builder()
                                .toolName("itinerary_review_agent")
                                .description("委托行程审核子智能体对行程方案进行五维结构化审核（完整性/时间/出发目的地/预算/偏好/路径），" +
                                             "返回审核报告与具体修改建议。参数：message（包含审核所需的上下文说明，如政策/偏好）。")
                                .forwardEvents(false)
                                .build())
                .apply();

        SkillBox skillBox = buildPlanningSkillBox(toolkit);

        // 在 build() 时捕获 sessionId（TransmittableThreadLocal，各 prototype 实例独立）
        AgentSessionContext sessionCtx = AgentSessionContextHolder.get();
        String capturedSessionId = sessionCtx != null ? sessionCtx.getSessionId() : null;
        LongTermMemory longTermMemory = createLongTermMemory(memoryFactory);

        // 创建 PlanNotebook，并注册变化钩子向前端推送任务列表进度
        PlanNotebook planNotebook = PlanNotebook.builder()
                .needUserConfirm(false)
                .build();

        if (capturedSessionId != null) {
            planNotebook.addChangeHook("plan_progress_notifier",
                    (nb, plan) -> progressNotifierHook.sendPlanUpdate(capturedSessionId, plan));
        }

        AutoContextMemory memory = AgentMemoryFactory.create(stableModel);

        ReActAgent agent = ReActAgent.builder()
                .name("ItineraryPlanAgent")
                .description("智能行程规划专家，负责方案生成、比价分析、审核修复闭环")
                .model(strongModelWithThinking)
                .memory(memory)
                .longTermMemory(longTermMemory)
                .longTermMemoryMode(LongTermMemoryMode.AGENT_CONTROL)
                .toolkit(toolkit)
                // 从 ThreadLocal 读取当前请求的会话上下文，用于 ToolExecutionContext 注入
                .toolExecutionContext(createToolCtx())
                .skillBox(skillBox)
                .hooks(List.of(dynamicTimeInjectionHook, new AutoContextHook(), new PlanAwareThinkingHook(planNotebook, 2048), cliResultCompressHook, flightApiKeyHook, tuniuApiKeyHook, rghUserIsolationHook,
                        skillContentCollapseHook, sessionPersistenceHook, executionLoggerHook, toolCircuitBreakerHook,
                        progressNotifierHook, activeAgentPersistenceHook,
                        new PendingToolRecoveryHook()))
                .planNotebook(planNotebook)
                // 子 agent（itinerary_review_agent）作为工具调用，单次可能含数十秒 LLM 推理，
                .toolExecutionConfig(getToolConfig())
                .sysPrompt(PromptLoader.loadStatic("itinerary-plan-agent-system.md"))
                .maxIters(MAX_ITERATIONS)
                .generateOptions(getEnableThinkingGenerateOptions(2048))
                .build();

        return agent;
    }

    private SkillBox buildPlanningSkillBox(Toolkit toolkit) {
        SkillBox skillBox = new SkillBox(toolkit);
        skillBox.registerSkill(skillRepository.getSkill("tuniu-cli"));
        ShellCommandTool shellTool = new ShellCommandTool(
                null,
                Set.of("curl", "npm", "tuniu", "rgh", "nohup", "sleep", "cat", "grep", "node", "date", "env", "which", "bash", "ls"),
                null);
        skillBox.codeExecution().withShell(shellTool).withRead().withWrite().enable();
        return skillBox;
    }
}
