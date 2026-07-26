package com.travel.agent;

import com.travel.agent.common.PromptLoader;
import com.travel.agent.memory.AgentMemoryFactory;
import com.travel.agent.order.TravelOrderConflictTools;
import com.travel.agent.tools.BookingWriteTools;
import com.travel.agent.tools.TravelOrderWriteTools;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.hook.PendingToolRecoveryHook;
import io.agentscope.core.memory.autocontext.AutoContextHook;
import io.agentscope.core.tool.Toolkit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

import java.util.List;

/**
 * @author Hollis
 */
@Configuration("itineraryManageAgentConfiguration")
public class ItineraryManageAgent extends BaseSubAgent {

    /**
     * 差旅单写工具集（submit_travel_approval / query_approval_status / cancel / modify）。
     */
    @Autowired
    protected TravelOrderWriteTools travelOrderWriteTools;

    /**
     * 行程冲突检测工具（check_travel_order_conflicts）。
     */
    @Autowired
    protected TravelOrderConflictTools travelOrderConflictTools;

    /**
     * 预订记录写工具集（cancel_booking）。
     */
    @Autowired
    protected BookingWriteTools bookingWriteTools;

    /**
     * 行程管理 Agent 最大推理迭代次数
     */
    private static final int MAX_ITERATIONS = 10;

    @Bean(name = "itineraryManageAgent")
    @Scope("prototype")
    public ReActAgent build() {

        Toolkit toolkit = new Toolkit();
        // submit/query_approval/cancel/modify
        toolkit.registration().tool(travelOrderWriteTools).apply();
        // query_travel_order
        toolkit.registration().tool(travelOrderReadTools).apply();
        // query_booking_record（查询外部预订记录：机票/酒店/火车票等）
        toolkit.registration().tool(bookingReadTools).apply();
        // query_travel_policy/check_travel_policy
        toolkit.registration().tool(policyTools).apply();
        // check_travel_order_conflicts（行程冲突检测：时间重叠 + 跨城衍接）
        toolkit.registration().tool(travelOrderConflictTools).apply();
        // cancel_booking（取消外部预订记录）
        toolkit.registration().tool(bookingWriteTools).apply();
        toolkit.registration().tool(queryUserInfoTools).apply();

        ReActAgent agent = ReActAgent.builder()
                .name("ItineraryManageAgent")
                .description("行程单全生命周期管理助手，负责收集信息、提交审批、查询差旅单/审批状态、取消出差申请、修改出差申请")
                .model(strongModel)
                .toolkit(toolkit)
                // 从 ThreadLocal 读取当前请求的会话上下文，用于 ToolExecutionContext 注入
                .toolExecutionContext(createToolCtx())
                // 自动压缩/卸载大工具结果与历史，抑制多轮输入 token 膨胀（需搭配 AutoContextHook）
                .memory(AgentMemoryFactory.create(stableModel))
                .hooks(List.of(dynamicTimeInjectionHook, new AutoContextHook(), executionLoggerHook, progressNotifierHook, sessionPersistenceHook, activeAgentPersistenceHook,
                        new PendingToolRecoveryHook()))
                .sysPrompt(PromptLoader.loadStatic("itinerary-manage-agent-system.md"))
                .maxIters(MAX_ITERATIONS)
                .toolExecutionConfig(getToolConfig())
                .generateOptions(getGenerateOptions())
                .build();

        return agent;
    }
}
