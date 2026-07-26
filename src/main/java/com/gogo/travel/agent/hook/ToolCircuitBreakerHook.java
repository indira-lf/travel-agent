package com.gogo.travel.agent.hook;

import com.gogo.travel.agent.circuitbreaker.CircuitBreakerProperties;
import com.gogo.travel.agent.circuitbreaker.CircuitBreakStore;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolGroup;
import io.agentscope.core.tool.Toolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Set;

/**
 * Tool 维度熔断降级 Hook。
 *
 * <p><b>状态机唯一权威：{@link #handlePreReasoning}。</b>它根据 Redis 中的 OPEN 状态
 * 与冷却期决定每个受监控的 tool 是"存在于 group"还是"被卸载"。{@link #handlePostActing}
 * 只做"统计 + 立即卸载"——写 Redis 失败计数 / OPEN 状态 / 降级代数，并立刻把 tool 从 group
 * 移除（不等下一次 PreReasoning）。</p>
 *
 * <p>状态机：</p>
 * <pre>
 *   CLOSED ─失败×N──> OPEN ──冷却期过──> HALF_OPEN ──成功──> CLOSED
 *                       ↑                  │
 *                       └────失败──────────┘
 * </pre>
 *
 * <p><b>错误识别：</b>仅识别框架异常前缀 {@code [ERROR]}。本 hook 不针对具体业务字段做特殊识别。</p>
 *
 * <p><b>日志分级：</b></p>
 * <ul>
 *   <li>INFO  —— 启动加载、熔断触发、状态翻转、半开期成功恢复</li>
 *   <li>WARN  —— 兜底命中（理论不该发生但需要排查时序）、异常情况</li>
 *   <li>DEBUG —— 每次事件入口、各 early-return 路径、状态判定、循环</li>
 * </ul>
 *
 * @author Hollis
 */
public class ToolCircuitBreakerHook implements Hook {

    private static final Logger logger = LoggerFactory.getLogger(ToolCircuitBreakerHook.class);

    public static final String TOOL_CIRCUIT_BREAKER_GROUP = "tool_circuit_breaker_group";

    /**
     * 框架对异常工具结果统一添加的前缀。
     */
    private static final String ERROR_PREFIX = "Tool execution failed";

    private final CircuitBreakerProperties properties;
    private final CircuitBreakStore failureStore;
    private final Set<String> monitoredToolNames;

    public ToolCircuitBreakerHook(CircuitBreakerProperties properties,
                                  CircuitBreakStore failureStore) {
        this.properties = properties;
        this.failureStore = failureStore;
        this.monitoredToolNames = properties.parseMonitoredToolNames();
        // 启动时打印一次加载的配置，方便确认 hook 是否生效、白名单是否正确
        logger.info("[ToolCB] 初始化完成 enabled={}, monitoredToolNames={}, " +
                    "failureThreshold={}, initialCooldownSeconds={}, maxCooldownSeconds={}, " +
                    "redisKeyPrefix={}",
                properties.isEnabled(),
                monitoredToolNames,
                properties.getFailureThreshold(),
                properties.getInitialCooldownSeconds(),
                properties.getMaxCooldownSeconds(),
                properties.getRedisKeyPrefix());
    }

    @Override
    public int priority() {
        // 数值越小越先执行；放在 RghUserIsolationHook (5) 之后
        return 6;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (!properties.isEnabled()) {
            // 关闭时不打印每次事件，避免日志噪音
            return Mono.just(event);
        }
        if (event instanceof PostActingEvent postActing) {
            logger.debug("[ToolCB] onEvent 收到事件 eventType={}", event.getClass().getSimpleName());
            return handlePostActing(postActing).map(e -> (T) e);
        }
        if (event instanceof PreReasoningEvent preReasoning) {
            logger.debug("[ToolCB] onEvent 收到事件 eventType={}", event.getClass().getSimpleName());
            return handlePreReasoning(preReasoning).map(e -> (T) e);
        }
        return Mono.just(event);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // PostActingEvent：失败/成功统计 + 立即熔断（从所在 group 移除 tool）
    // ──────────────────────────────────────────────────────────────────────────

    private Mono<PostActingEvent> handlePostActing(PostActingEvent event) {
        ToolUseBlock toolUse = event.getToolUse();
        ToolResultBlock toolResult = event.getToolResult();
        if (toolUse == null || toolResult == null) {
            logger.debug("[ToolCB] PostActing 跳过原因=toolUse/toolResult 为 null, " +
                         "toolUse==null={}, toolResult==null={}",
                    toolUse == null, toolResult == null);
            return Mono.just(event);
        }
        if (toolResult.isSuspended()) {
            logger.debug("[ToolCB] PostActing 跳过原因=工具被挂起(等待用户输入), toolName={}",
                    toolUse.getName());
            return Mono.just(event);
        }
        Toolkit toolkit = event.getToolkit();
        if (toolkit == null) {
            logger.debug("[ToolCB] PostActing 跳过原因=toolkit 为 null, toolName={}",
                    toolUse.getName());
            return Mono.just(event);
        }

        String toolName = toolUse.getName();
        if (toolName == null) {
            logger.debug("[ToolCB] PostActing 跳过原因=toolName 为 null, toolUse.id={}",
                    toolUse.getId());
            return Mono.just(event);
        }

        if (!monitoredToolNames.contains(toolName)) {
            // 不在白名单的 tool 最多打到 DEBUG，避免每次 LLM reasoning 都刷屏
            logger.debug("[ToolCB] PostActing 跳过原因=toolName 不在白名单, toolName={}", toolName);
            return Mono.just(event);
        }

        boolean isError = isErrorResult(toolResult);
        boolean open = failureStore.isOpen(toolName);
        boolean cooldownExpired = failureStore.isCooldownExpired(toolName);
        logger.debug("[ToolCB] PostActing 进入统计 toolName={}, isError={}, isOpen={}, " +
                     "isCooldownExpired={}, currentFailCount={}, currentGeneration={}",
                toolName, isError, open, cooldownExpired,
                failureStore.getFailureCount(toolName), failureStore.getGeneration(toolName));

        // OPEN 且冷却期未到：模型应已看不到 tool，但仍兜底（极端时序下可能 LLM 已决策调用）
        if (open && !cooldownExpired) {
            logger.warn("[ToolCB] PostActing 兜底命中: 工具处于 OPEN 冷却中却仍被调用, " +
                        "toolName={}, toolUse.id={} —— 可能是 LLM 在熔断前已决策调用",
                    toolName, toolUse.getId());
            return Mono.just(event);
        }

        // 进入此处时，隐含 cooldownExpired==true，即处于半开期或未熔断状态
        boolean halfOpen = open;

        if (isError) {
            if (halfOpen) {
                long cooldown = failureStore.openWithNextGeneration(toolName);
                removeToolFromGroup(toolkit, toolName);
                logger.warn("[ToolCB] ⚠️  半开期再次失败 toolName={}, 重新熔断, 冷却={}s, " +
                            "所在 groups={}", toolName, cooldown,
                        TOOL_CIRCUIT_BREAKER_GROUP);
            } else {
                long count = failureStore.incrementFailureCount(toolName);
                if (count >= properties.getFailureThreshold()) {
                    long cooldown = failureStore.openWithNextGeneration(toolName);
                    removeToolFromGroup(toolkit, toolName);
                    logger.warn("[ToolCB] ⚠️  Tool 已熔断 toolName={}, 连续失败={}, 冷却={}s, " +
                                "所在 groups={}", toolName, count, cooldown,
                            TOOL_CIRCUIT_BREAKER_GROUP);
                } else {
                    logger.info("[ToolCB] 工具执行失败 toolName={}, 连续失败={}/{}, 距阈值还差 {} 次",
                            toolName, count, properties.getFailureThreshold(),
                            properties.getFailureThreshold() - count);
                }
            }
        } else {
            // 成功路径：半开期探测成功/未熔断状态重置计数都只改 Redis，不动 toolkit
            // （恢复交给下一次 PreReasoning 统一处理，避免在 PostActing 里反复 addTool）
            if (halfOpen) {
                failureStore.clearOpen(toolName);
                failureStore.resetFailureCount(toolName);
                logger.info("[ToolCB] ✅ Tool 已完全恢复 toolName={}", toolName);
            } else if (failureStore.getFailureCount(toolName) > 0) {
                long prev = failureStore.getFailureCount(toolName);
                failureStore.resetFailureCount(toolName);
                logger.info("[ToolCB] 工具执行成功，重置失败计数 toolName={}, 已累计 {} 次被清零",
                        toolName, prev);
            } else {
                // 健康状态下的成功也打一下，方便确认 hook 一直在监听
                logger.debug("[ToolCB] 工具执行成功 toolName={}（健康状态，无计数变化）", toolName);
            }
        }

        return Mono.just(event);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // PreReasoningEvent：状态机唯一权威，根据 Redis 把 tool 从所在 group 卸载/加回
    // ──────────────────────────────────────────────────────────────────────────

    private Mono<PreReasoningEvent> handlePreReasoning(PreReasoningEvent event) {
        // PreReasoningEvent 没有 getToolkit()，需要从 agent 上取。
        Toolkit toolkit = (event.getAgent() instanceof ReActAgent reactAgent)
                ? reactAgent.getToolkit()
                : null;

        if (toolkit == null) {
            logger.warn("[ToolCB] PreReasoning 跳过原因=toolkit 为 null, " +
                        "agent 类型={}（非 ReActAgent 或未挂载 toolkit）",
                    event.getAgent() != null ? event.getAgent().getClass().getSimpleName() : "null");
            return Mono.just(event);
        }

        if (logger.isDebugEnabled()) {
            List<String> activeGroups = toolkit.getActiveGroups();
            logger.debug("[ToolCB] PreReasoning 同步开始 activeGroups={}, monitoredTools={}",
                    activeGroups, monitoredToolNames);
        }

        // 唯一规则：处于 OPEN 状态且未过冷却期 → 卸载；其它情况（CLOSED / 半开期）→ 加回
        for (String toolName : monitoredToolNames) {
            boolean open = failureStore.isOpen(toolName);
            boolean cooldownExpired = failureStore.isCooldownExpired(toolName);
            boolean shouldUninstall = open && !cooldownExpired;
            logger.debug("[ToolCB] PreReasoning 判定 toolName={}, isOpen={}, " +
                         "isCooldownExpired={}, shouldUninstall={}, generation={}",
                    toolName, open, cooldownExpired, shouldUninstall,
                    failureStore.getGeneration(toolName));
            applyToolAvailability(toolkit, toolName, shouldUninstall);
        }
        return Mono.just(event);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 辅助
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 根据 {@code shouldUninstall} 在所有 active group 中将 tool 卸载或加回。
     * 同一 tool 可能被多个 active group 注册（本框架下不常见但理论可能）。
     */
    private void applyToolAvailability(Toolkit toolkit, String toolName, boolean shouldUninstall) {
        ToolGroup group = toolkit.getToolGroup(TOOL_CIRCUIT_BREAKER_GROUP);

        if (group == null) {
            return;
        }
        boolean contains = group.containsTool(toolName);
        if (shouldUninstall && contains) {
            try {
                group.removeTool(toolName);
                logger.info("[ToolCB] 卸载 tool toolName={}, groupName={}", toolName, TOOL_CIRCUIT_BREAKER_GROUP);
            } catch (Exception e) {
                logger.error("[ToolCB] 卸载 tool 失败 toolName={}, groupName={}",
                        toolName, TOOL_CIRCUIT_BREAKER_GROUP, e);
            }
        } else if (!shouldUninstall && !contains) {
            try {
                group.addTool(toolName);
                logger.info("[ToolCB] 恢复 tool toolName={}, groupName={}", toolName, TOOL_CIRCUIT_BREAKER_GROUP);
            } catch (Exception e) {
                logger.error("[ToolCB] 恢复 tool 失败 toolName={}, groupName={}",
                        toolName, TOOL_CIRCUIT_BREAKER_GROUP, e);
            }
        } else {
            // 状态一致，不需要动作
            logger.debug("[ToolCB] tool 状态已符合预期, toolName={}, groupName={}, " +
                         "contains={}, shouldUninstall={}",
                    toolName, TOOL_CIRCUIT_BREAKER_GROUP, contains, shouldUninstall);
        }
    }

    /**
     * 立即从所有包含该 tool 的 active group 卸载（PostActing 触发熔断时调用）。
     * 该操作是幂等的，PreReasoning 下一次还会再同步一次。
     */
    private void removeToolFromGroup(Toolkit toolkit, String toolName) {
        applyToolAvailability(toolkit, toolName, true);
    }

    /**
     * 判断工具结果是否为错误。仅识别框架统一异常前缀 {@code [ERROR]}。
     */
    private boolean isErrorResult(ToolResultBlock toolResult) {
        List<ContentBlock> output = toolResult.getOutput();
        if (output == null) {
            return false;
        }
        for (ContentBlock block : output) {
            if (!(block instanceof TextBlock text)) {
                continue;
            }
            String content = text.getText();
            if (content == null) {
                continue;
            }
            if (content.contains(ERROR_PREFIX)) {
                return true;
            }
        }
        return false;
    }
}
