package com.gogo.travel.agent.hook;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.hook.PreSummaryEvent;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.plan.PlanNotebook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

public class PlanAwareThinkingHook implements Hook {

    private static final Logger logger = LoggerFactory.getLogger(PlanAwareThinkingHook.class);

    private final PlanNotebook planNotebook;
    private final int thinkingBudget;

    public PlanAwareThinkingHook(PlanNotebook planNotebook, int thinkingBudget) {
        this.planNotebook = planNotebook;
        this.thinkingBudget = thinkingBudget;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        switch (event) {
            case PreReasoningEvent e -> handleReasoning(e);
            case PreSummaryEvent e -> enableThinking(e);  // final summary 开启
            default -> {
            }
        }
        return Mono.just(event);
    }

    private void handleReasoning(PreReasoningEvent event) {
        boolean planning = isPlanning();
        logger.debug("[PLAN_THINKING] agent={}, model={}, isPlanning={}, currentPlan={}",
                event.getAgent() != null ? event.getAgent().getName() : "unknown",
                event.getModelName(),
                planning,
                planNotebook.getCurrentPlan());
        if (planning) {
            // Planning 阶段：什么都不用做，默认用思考模式。
        } else {
            // Execute 阶段：关闭思考，快速执行
            disableThinking(event);
        }
    }

    /**
     * Plan 未创建 = 还在 Planning 阶段
     */
    private boolean isPlanning() {
        return planNotebook.getCurrentPlan() == null;
    }


    private void enableThinking(PreSummaryEvent event) {
        logger.debug("[PLAN_THINKING] Summary 阶段 → 开启深度思考, thinkingBudget={}", thinkingBudget);
        event.setGenerateOptions(GenerateOptions.builder()
                .cacheControl(true)
                .thinkingBudget(thinkingBudget)
                .build());
    }

    private void disableThinking(PreReasoningEvent event) {
        logger.debug("[PLAN_THINKING] Execute 阶段 → 关闭思考 (enable_thinking=false)");
        event.setGenerateOptions(GenerateOptions.builder()
                // 保住隐式缓存
                .cacheControl(true)
                // 关闭思考
                .additionalBodyParam("enable_thinking", false)
                .build());
    }
}
