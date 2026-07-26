package com.travel.agent;

import com.travel.agent.common.PromptLoader;
import com.travel.agent.hook.*;
import com.travel.agent.memory.AgentMemoryFactory;
import com.travel.agent.tools.ApiKeyTools;
import com.travel.agent.tools.BookingWriteTools;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.hook.PendingToolRecoveryHook;
import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.memory.LongTermMemoryMode;
import io.agentscope.core.memory.autocontext.AutoContextHook;
import io.agentscope.core.memory.autocontext.AutoContextMemory;
import io.agentscope.core.skill.SkillBox;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.ToolkitConfig;
import io.agentscope.core.tool.coding.ShellCommandTool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

import java.util.List;
import java.util.Set;

/**
 * 预订执行智能体。
 *
 * <p>负责用户确认行程方案后的实际预订执行（出票/订房/订火车票）及已有预订的取消。
 * 由 MasterAgent 根据 booking 意图路由调用，与 ItineraryPlanAgent 平级。
 *
 * <p>核心能力：
 * <ul>
 *   <li>通过 tuniu-cli 技能执行下单命令</li>
 *   <li>通过 BookingPersistenceHook 自动将下单结果落库</li>
 *   <li>通过 cancel_booking 工具取消已有预订</li>
 *   <li>查询预订记录供用户核查</li>
 * </ul>
 *
 * @author Hollis
 */
@Configuration("bookingAgentConfiguration")
public class BookingAgent extends BaseSubAgent {

    /**
     * 预订 Agent 最大推理迭代次数
     */
    private static final int MAX_ITERATIONS = 10;

    /**
     * 第三方 API Key 统一管理工具（check_/save_flight_api_key、check_/save_tuniu_api_key）。
     */
    @Autowired
    protected ApiKeyTools apiKeyTools;

    @Autowired
    protected FlightApiKeyHook flightApiKeyHook;

    @Autowired
    private RghUserIsolationHook rghUserIsolationHook;

    @Autowired
    protected TuniuApiKeyHook tuniuApiKeyHook;

    @Autowired
    protected BookingPersistenceHook bookingPersistenceHook;

    @Autowired
    protected SkillContentCollapseHook skillContentCollapseHook;

    /**
     * 预订记录写工具集（cancel_booking）。
     */
    @Autowired
    protected BookingWriteTools bookingWriteTools;

    @Bean(name = "bookingAgent")
    @Scope("prototype")
    public ReActAgent build() {
        Toolkit toolkit = new Toolkit(ToolkitConfig.builder()
                .parallel(true)
                .build());
        // 查询已审批差旅单（获取预订上下文）
        toolkit.registration().tool(travelOrderReadTools).apply();
        // 查询外部预订记录（机票/酒店/火车票等）
        toolkit.registration().tool(bookingReadTools).apply();
        // 取消预订
        toolkit.registration().tool(bookingWriteTools).apply();
        // 用户信息查询
        toolkit.registration().tool(queryUserInfoTools).apply();
        // API Key 管理
        toolkit.registration().tool(apiKeyTools).apply();

        SkillBox skillBox = buildBookingSkillBox(toolkit);

        LongTermMemory longTermMemory = createLongTermMemory(memoryFactory);

        AutoContextMemory memory = AgentMemoryFactory.create(stableModel);

        ReActAgent agent = ReActAgent.builder()
                .name("BookingAgent")
                .description("预订执行专家，负责用户确认方案后的出票/订房/订火车票及已有预订的取消")
                .model(strongModelWithThinking)
                .memory(memory)
                .longTermMemory(longTermMemory)
                .longTermMemoryMode(LongTermMemoryMode.AGENT_CONTROL)
                .toolkit(toolkit)
                .toolExecutionContext(createToolCtx())
                .skillBox(skillBox)
                .hooks(List.of(dynamicTimeInjectionHook, new AutoContextHook(), cliResultCompressHook,
                        flightApiKeyHook, tuniuApiKeyHook, rghUserIsolationHook,
                        skillContentCollapseHook, sessionPersistenceHook, executionLoggerHook,
                        toolCircuitBreakerHook, progressNotifierHook, activeAgentPersistenceHook,
                        bookingPersistenceHook, new PendingToolRecoveryHook()))
                .toolExecutionConfig(getToolConfig())
                .sysPrompt(PromptLoader.loadStatic("booking-agent-system.md"))
                .maxIters(MAX_ITERATIONS)
                .generateOptions(getEnableThinkingGenerateOptions(2048))
                .build();

        return agent;
    }

    private SkillBox buildBookingSkillBox(Toolkit toolkit) {
        SkillBox skillBox = new SkillBox(toolkit);
        skillBox.registerSkill(skillRepository.getSkill("tuniu-cli"));
        ShellCommandTool shellTool = new ShellCommandTool(
                null,
                Set.of("tuniu", "env", "bash", "cat", "grep", "date", "which", "ls"),
                null);
        skillBox.codeExecution().withShell(shellTool).withRead().withWrite().enable();
        return skillBox;
    }
}
