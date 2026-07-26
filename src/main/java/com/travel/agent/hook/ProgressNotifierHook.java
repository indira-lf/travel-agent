package com.travel.agent.hook;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.travel.agent.common.TravelDataNormalizer;
import com.travel.agent.registry.SseEmitterRegistry;
import com.travel.config.sensitive.SensitiveMasker;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.hook.*;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.plan.model.Plan;
import io.agentscope.core.plan.model.SubTask;
import io.agentscope.core.plan.model.SubTaskState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 监听 Agent 执行生命周期，通过 {@link SseEmitter} 向前端实时推送四类 SSE 事件：
 * <ul>
 *   <li>{@code progress}    —— agent 启停 / 工具调用进度</li>
 *   <li>{@code thinking}    —— agent 推理增量文本；分析类 Agent 的最终格式化结果</li>
 *   <li>{@code plan_update} —— PlanNotebook 任务列表变化（由外部调用 {@link #sendPlanUpdate}）</li>
 *   <li>{@code travel_data} —— 从工具结果中识别到的酒店 / 机票 / 火车票等可展示数据</li>
 * </ul>
 *
 * @author Hollis
 */
public class ProgressNotifierHook implements Hook {

    private static final Logger logger = LoggerFactory.getLogger(ProgressNotifierHook.class);

    // ============ 常量：Agent / 工具 元数据 ============

    /** Agent 名称 → 中文标签 */
    private static final Map<String, String> AGENT_LABELS = Map.of(
            "MasterAgent", "智能中枢",
            "QueryRewritingAgent", "查询改写",
            "IntentRecognitionAgent", "意图识别",
            "ItineraryPlanAgent", "行程规划",
            "ItineraryReviewAgent", "行程审核",
            "ItineraryManageAgent", "行程管理",
            "ReimbursementAgent", "报销处理",
            "InfoAgent", "信息查询"
    );

    /** 流水线分析类 Agent：LLM 输出为 JSON，需累积后统一格式化推送，而非原始流式推送 */
    private static final Set<String> ANALYSIS_AGENTS = Set.of("QueryRewritingAgent", "IntentRecognitionAgent");

    /** 子 Agent 代理工具：其自身会产生独立进度，此处不再作为工具条目上报 */
    private static final Set<String> AGENT_TOOLS = Set.of(
            "itinerary_manage_agent", "info_agent", "itinerary_plan_agent", "itinerary_review_agent"
    );

    /** PlanNotebook 内部工具：其状态已通过 plan_update 呈现，避免和工具条目重复展示 */
    private static final Set<String> PLAN_TOOLS = Set.of(
            "create_plan", "update_plan_info", "revise_current_plan", "update_subtask_state",
            "finish_subtask", "view_subtasks", "get_subtask_count", "finish_plan",
            "view_historical_plans", "recover_historical_plan"
    );

    /** Human-in-the-Loop 工具：前端通过 user_interaction 事件单独渲染，不走进度条目 */
    private static final Set<String> HITL_TOOLS = Set.of("ask_user");

    /** 工具名 → 中文标签 */
    private static final Map<String, String> TOOL_LABELS = Map.ofEntries(
            // 差旅政策
            Map.entry("check_travel_policy", "核对差旅政策"),
            Map.entry("query_travel_policy", "查询差旅政策"),
            // 差旅申请 / 审批
            Map.entry("query_travel_order", "查询差旅申请"),
            Map.entry("modify_travel_order", "修改出差申请"),
            Map.entry("cancel_travel_order", "取消出差申请"),
            Map.entry("submit_travel_approval", "提交审批申请"),
            Map.entry("query_approval_status", "查询审批进度"),
            Map.entry("check_travel_order_conflicts", "检查行程冲突"),
            // 路由 / 长期记忆
            Map.entry("retrieveFromMemory", "回忆您的偏好"),
            Map.entry("recordToMemory", "记住您的偏好"),
            // 用户信息
            Map.entry("query_user_base_location", "查询常驻城市"),
            Map.entry("query_user_contact_info", "查询联系人信息"),
            Map.entry("update_user_contact_info", "更新联系人信息"),
            // 行程
            Map.entry("review_planner_result", "审核出行方案"),
            // 目的地信息
            Map.entry("query_info", "查询出行信息"),
            Map.entry("query_weather", "查询目的地天气"),
            Map.entry("query_destination_news", "查询目的地资讯"),
            // 报销
            Map.entry("ocr_invoice", "识别发票"),
            Map.entry("generate_expense_report", "生成报销单"),
            Map.entry("submit_reimbursement", "提交报销申请"),
            // 服务连接 / 密钥
            Map.entry("check_flight_api_key", "检查机票服务连接"),
            Map.entry("save_flight_api_key", "保存机票服务密钥"),
            Map.entry("check_tuniu_api_key", "检查途牛服务连接"),
            Map.entry("save_tuniu_api_key", "保存途牛服务密钥"),
            // 签证（Orizn Visa MCP）
            Map.entry("quick_visa_check", "快速签证检查"),
            Map.entry("check_visa_requirement", "查询签证详细要求"),
            Map.entry("get_all_destinations", "查询可办签国家"),
            Map.entry("get_visa_changes", "查询签证政策变更"),
            Map.entry("check_transit_visa", "查询中转签证规则"),
            Map.entry("get_coverage_stats", "查询签证数据覆盖"),
            // 知识库 / 技能 / 系统
            Map.entry("retrieve_knowledge", "查询知识库"),
            Map.entry("load_skill_through_path", "启用专业技能"),
            Map.entry("execute_shell_command", "执行查询操作"),
            Map.entry("write_text_file", "保存文件"),
            Map.entry("plan_itinerary", "规划往返行程")
    );

    /** Skill ID → 中文标签（用于 load_skill_through_path 工具的参数解析） */
    private static final Map<String, String> SKILL_LABELS = Map.ofEntries(
            Map.entry("flight-manager_classpath-skills", "Skill：机票查询"),
            Map.entry("rolling-go-hotel_classpath-skills", "Skill：酒店查询"),
            Map.entry("reimbursement_classpath-skills", "Skill：报销处理"),
            Map.entry("tuniu-cli_classpath-skills", "Skill：途牛旅行服务"),
            Map.entry("flyai_classpath-skills", "Skill：飞猪旅行服务"),
            // 兼容不带 _classpath-skills 后缀的纯 skillId 形式
            Map.entry("flight-manager", "机票查询"),
            Map.entry("rolling-go-hotel", "酒店查询"),
            Map.entry("reimbursement", "报销处理"),
            Map.entry("tuniu-cli", "途牛旅行服务"),
            Map.entry("flyai", "智能机票助手")
    );

    /** 意图 ID → 中文（用于 IntentRecognitionAgent 结果格式化） */
    private static final Map<String, String> INTENT_LABELS = Map.ofEntries(
            Map.entry("itinerary_planning", "行程规划"),
            Map.entry("flight_booking", "机票预订"),
            Map.entry("hotel_booking", "酒店预订"),
            Map.entry("train_booking", "高铁预订"),
            Map.entry("reimbursement", "报销处理"),
            Map.entry("travel_order_query", "订单查询"),
            Map.entry("approval", "审批申请"),
            Map.entry("info_query", "信息查询"),
            Map.entry("cancel_order", "取消订单"),
            Map.entry("modify_order", "修改订单")
    );

    /** 置信度 → 中文 */
    private static final Map<String, String> CONFIDENCE_LABELS = Map.of("high", "高", "medium", "中", "low", "低");

    /** 工具执行结果文本推送上限（字符数），超出截断 */
    private static final int RESULT_TEXT_MAX_LENGTH = 800;
    /** 工具入参 JSON 推送上限（字符数），超出截断 */
    private static final int ARGUMENTS_TEXT_MAX_LENGTH = 500;
    /** travel_data 事件每次最多展示的条目数 */
    private static final int TRAVEL_DATA_MAX_ITEMS = 5;

    // ============ 依赖与运行时状态 ============

    private final SseEmitterRegistry emitterRegistry;
    private final TravelDataNormalizer travelDataNormalizer = new TravelDataNormalizer();

    /**
     * Agent Id → sessionId 缓存。
     * ReasoningChunkEvent 由 AgentScope 用裸 {@code .subscribe()} 触发，Reactor Context 传播链可能断裂；
     * 其他事件在 Context 完整时写入此缓存，供后续事件（尤其是 chunk）回退使用。
     */
    private final ConcurrentHashMap<String, String> agentSessionCache = new ConcurrentHashMap<>();

    /**
     * {@code sessionId:agentName} → 分析类 Agent 的原始输出累积。
     * QueryRewritingAgent / IntentRecognitionAgent 的 LLM 输出是 JSON，逐字节流式到来，
     * 在此累积后由 {@link PostCallEvent} 统一解析并推送。
     */
    private final ConcurrentHashMap<String, StringBuilder> analysisAccumulator = new ConcurrentHashMap<>();

    public ProgressNotifierHook(SseEmitterRegistry emitterRegistry) {
        this.emitterRegistry = emitterRegistry;
    }

    // ============ Hook 入口 ============

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        // 快速过滤不关心的事件，避免不必要的 Context 解析和缓存操作
        if (!isHandled(event)) {
            return Mono.just(event);
        }
        return Mono.deferContextual(ctx -> {
            String sessionId = resolveSessionId(event, ctx);
            if (sessionId == null) {
                logger.debug("[ProgressNotifierHook] 未提取到 sessionId，跳过进度推送，eventType={}",
                        event.getClass().getSimpleName());
                return Mono.just(event);
            }
            // 成功解析后写入缓存，供当前 Agent 调用周期内后续事件回退使用
            agentSessionCache.put(event.getAgent().getAgentId(), sessionId);

            SseEmitter emitter = emitterRegistry.get(sessionId);
            if (emitter == null) {
                return Mono.just(event);
            }

            String agentName = getAgentName(event);
            try {
                dispatch(event, emitter, sessionId, agentName);
            } catch (Exception e) {
                logger.warn("[PROGRESS] 发送进度事件失败 sessionId={}, agent={}: {}",
                        sessionId, agentName, e.getMessage());
            }
            return Mono.just(event);
        });
    }

    private static boolean isHandled(HookEvent event) {
        return event instanceof PreCallEvent
                || event instanceof PreReasoningEvent
                || event instanceof PreActingEvent
                || event instanceof PostActingEvent
                || event instanceof PostCallEvent
                || event instanceof ReasoningChunkEvent;
    }

    private String resolveSessionId(HookEvent event, ContextView ctx) {
        String cached = agentSessionCache.get(event.getAgent().getAgentId());
        if (cached != null) {
            return cached;
        }
        if (event instanceof ReasoningChunkEvent) {
            // ReasoningChunkEvent 场景下 Reactor Context 常已断裂，改从 memory metadata 取 sessionId
            return extractSessionId(event.getMemory());
        }
        return ctx.getOrDefault("sessionId", null);
    }

    private void dispatch(HookEvent event, SseEmitter emitter, String sessionId, String agentName) {
        if (event instanceof PreCallEvent) {
            onPreCall(emitter, agentName);
        } else if (event instanceof PreReasoningEvent) {
            onPreReasoning(emitter, agentName);
        } else if (event instanceof PreActingEvent preActing) {
            onPreActing(emitter, agentName, preActing);
        } else if (event instanceof PostActingEvent postActing) {
            onPostActing(emitter, agentName, postActing);
        } else if (event instanceof PostCallEvent postCall) {
            onPostCall(emitter, sessionId, agentName, postCall);
        } else if (event instanceof ReasoningChunkEvent chunk) {
            onReasoningChunk(emitter, sessionId, agentName, chunk);
        }
    }

    // ============ 单事件处理器 ============

    private void onPreCall(SseEmitter emitter, String agentName) {
        String label = AGENT_LABELS.getOrDefault(agentName, agentName);
        sendProgress(emitter, buildAgentEvent("agent_start", agentName, label + " 处理中"));
    }

    private void onPreReasoning(SseEmitter emitter, String agentName) {
        // 分析类 Agent 通过累积后一次性推送 thinking，无需"新轮次"分割信号
        if (!ANALYSIS_AGENTS.contains(agentName)) {
            sendThinkingRoundStart(emitter, agentName);
        }
    }

    private void onPreActing(SseEmitter emitter, String agentName, PreActingEvent event) {
        ToolUseBlock toolUse = event.getToolUse();
        if (toolUse != null && isProgressReportableTool(toolUse.getName())) {
            sendProgress(emitter, buildToolEvent("tool_call", agentName, toolUse, null));
        }
    }

    private void onPostActing(SseEmitter emitter, String agentName, PostActingEvent event) {
        ToolUseBlock toolUse = event.getToolUse();
        if (toolUse == null || !isProgressReportableTool(toolUse.getName())) {
            return;
        }
        ToolResultBlock toolResult = event.getToolResult();
        String resultText = extractToolResultText(toolResult, RESULT_TEXT_MAX_LENGTH);
        sendProgress(emitter, buildToolEvent("tool_done", agentName, toolUse, resultText));
        // 尝试从工具结果中识别酒店/机票/火车票等可展示数据，另行推送 travel_data
        tryEmitTravelData(emitter, toolResult, toolUse.getName());
    }

    private void onPostCall(SseEmitter emitter, String sessionId, String agentName, PostCallEvent event) {
        String label = AGENT_LABELS.getOrDefault(agentName, agentName);
        sendProgress(emitter, buildAgentEvent("agent_done", agentName, label + " 已完成"));
        if (ANALYSIS_AGENTS.contains(agentName)) {
            flushAnalysisThinking(emitter, sessionId, agentName, event);
        }
        agentSessionCache.remove(event.getAgent().getAgentId());
    }

    private void onReasoningChunk(SseEmitter emitter, String sessionId, String agentName, ReasoningChunkEvent event) {
        String text = event.getIncrementalChunk() != null ? event.getIncrementalChunk().getTextContent() : null;
        if (text == null || text.isEmpty()) {
            return;
        }
        if (ANALYSIS_AGENTS.contains(agentName)) {
            // 分析类 Agent 输出的是 JSON，累积起来等 PostCallEvent 时统一格式化
            String accKey = sessionId + ":" + agentName;
            analysisAccumulator.computeIfAbsent(accKey, k -> new StringBuilder()).append(text);
        } else {
            sendThinking(emitter, agentName, text);
        }
    }

    // ============ 工具过滤 ============

    /**
     * 判断该工具是否应该作为普通进度条目上报。
     * 排除三类：子 Agent 代理（有独立进度）、Plan 内部工具（走 plan_update）、HITL（走 user_interaction）。
     */
    private static boolean isProgressReportableTool(String toolName) {
        return !AGENT_TOOLS.contains(toolName)
                && !PLAN_TOOLS.contains(toolName)
                && !HITL_TOOLS.contains(toolName);
    }

    // ============ 分析类 Agent thinking 输出 ============

    /**
     * 分析类 Agent 完成后推送格式化的 thinking：优先取增量累积的原始文本；
     * 累积为空时（如直接继承 {@code AgentBase}、内部以 {@code Model.stream(...)} 单次调用 LLM 的
     * {@code QueryRewritingAgent}，不会产生 {@link ReasoningChunkEvent}），
     * 回退到 {@link PostCallEvent#getFinalMessage()} 携带的最终文本。
     */
    private void flushAnalysisThinking(SseEmitter emitter, String sessionId, String agentName, PostCallEvent event) {
        String accKey = sessionId + ":" + agentName;
        StringBuilder accumulated = analysisAccumulator.remove(accKey);
        String rawText;
        if (accumulated != null && accumulated.length() > 0) {
            rawText = accumulated.toString();
        } else {
            Msg finalMsg = event.getFinalMessage();
            rawText = finalMsg != null ? finalMsg.getTextContent() : null;
        }
        if (rawText == null || rawText.isBlank()) {
            return;
        }
        String formatted = formatAnalysisResult(agentName, rawText);
        if (formatted != null && !formatted.isBlank()) {
            sendThinking(emitter, agentName, formatted);
        }
    }

    private String formatAnalysisResult(String agentName, String rawText) {
        try {
            JSONObject json = parseJsonFromText(rawText);
            if (json == null) {
                return null;
            }
            return switch (agentName) {
                case "QueryRewritingAgent" -> formatQueryRewriting(json);
                case "IntentRecognitionAgent" -> formatIntentRecognition(json);
                default -> null;
            };
        } catch (Exception e) {
            logger.debug("[PROGRESS] 格式化分析结果失败 agent={}: {}", agentName, e.getMessage());
            return null;
        }
    }

    /** 从文本中解析第一个 JSON 对象；直接解析失败时尝试截取 {@code {...}} 片段。 */
    private JSONObject parseJsonFromText(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String trimmed = text.trim();
        try {
            return JSON.parseObject(trimmed);
        } catch (Exception ignored) {
            int start = trimmed.indexOf('{');
            int end = trimmed.lastIndexOf('}');
            if (start >= 0 && end > start) {
                try {
                    return JSON.parseObject(trimmed.substring(start, end + 1));
                } catch (Exception e) {
                    logger.debug("[PROGRESS] JSON 提取失败: {}", e.getMessage());
                }
            }
            return null;
        }
    }

    /** 格式化 QueryRewritingAgent 结果：「[全新问题标签] + 原因 + 改写结果」 */
    private String formatQueryRewriting(JSONObject json) {
        StringBuilder sb = new StringBuilder();
        if (Boolean.FALSE.equals(json.getBoolean("related"))) {
            sb.append("《全新问题，与历史对话无关》\n");
        }
        String reason = json.getString("reason");
        if (reason != null && !reason.isBlank()) {
            sb.append("原因：").append(reason.trim()).append("\n");
        }
        String rewritten = json.getString("rewritten_question");
        if (rewritten != null && !rewritten.isBlank()) {
            sb.append("改写结果：").append(rewritten.trim());
        }
        return sb.toString().trim();
    }

    /** 格式化 IntentRecognitionAgent 结果：「识别结果：意图1（置信度）[、意图2...] + 原因」 */
    private String formatIntentRecognition(JSONObject json) {
        StringBuilder sb = new StringBuilder();
        JSONArray intents = json.getJSONArray("intents");
        if (intents != null && !intents.isEmpty()) {
            sb.append("识别结果：");
            for (int i = 0; i < intents.size(); i++) {
                JSONObject intent = intents.getJSONObject(i);
                if (i > 0) {
                    sb.append("、");
                }
                String intentId = intent.getString("intent");
                sb.append(INTENT_LABELS.getOrDefault(intentId, intentId));
                String confidence = intent.getString("confidence");
                if (confidence != null) {
                    sb.append("（置信度：")
                            .append(CONFIDENCE_LABELS.getOrDefault(confidence, confidence))
                            .append("）");
                }
            }
            sb.append("\n");
        }
        String overallReason = json.getString("overall_reason");
        if (overallReason != null && !overallReason.isBlank()) {
            sb.append("原因：").append(overallReason.trim());
        }
        return sb.toString().trim();
    }

    // ============ SSE 事件构建 ============

    private Map<String, String> buildAgentEvent(String type, String agentName, String message) {
        Map<String, String> data = new HashMap<>();
        data.put("type", type);
        data.put("stepId", "agent_" + agentName);
        data.put("agentName", agentName);
        data.put("message", message);
        return data;
    }

    private Map<String, String> buildToolEvent(String type, String agentName, ToolUseBlock toolUse, String result) {
        String toolName = toolUse.getName();
        String label = resolveToolLabel(toolUse);
        String message = "tool_done".equals(type) ? label + " 完成" : label;

        Map<String, String> data = new HashMap<>();
        data.put("type", type);
        data.put("stepId", "tool_" + toolUse.getId());
        data.put("agentName", agentName);
        data.put("toolName", toolName);
        data.put("message", message);
        if (result != null && !result.isBlank()) {
            data.put("result", result);
        }
        String arguments = extractToolArguments(toolUse);
        if (arguments != null) {
            data.put("arguments", arguments);
        }
        return data;
    }

    /** 返回工具的中文展示标签，{@code load_skill_through_path} 特殊处理为具体 Skill 名。 */
    private String resolveToolLabel(ToolUseBlock toolUse) {
        String toolName = toolUse.getName();
        if ("load_skill_through_path".equals(toolName)) {
            return resolveSkillLabel(toolUse);
        }
        return TOOL_LABELS.getOrDefault(toolName, toolName);
    }

    /**
     * 从 {@code load_skill_through_path} 参数中提取 skillId，映射为中文 Skill 名称。
     * 参数形如 {@code {"skillId":"tuniu-cli","path":"SKILL.md"}}，兼容 {@code skill_id} / {@code path} 兜底。
     */
    private String resolveSkillLabel(ToolUseBlock toolUse) {
        try {
            Object raw = toolUse.getInput();
            if (raw == null) {
                return "加载技能";
            }
            JSONObject json = (raw instanceof String s)
                    ? JSON.parseObject(s)
                    : JSON.parseObject(JSON.toJSONString(raw));

            String skillId = json.getString("skillId");
            if (skillId == null) {
                skillId = json.getString("skill_id");
            }
            if (skillId == null) {
                String path = json.getString("path");
                if (path != null) {
                    // 路径如 "skills/intent-recognition/SKILL.md" 或 "intent-recognition"
                    String[] parts = path.replace("\\", "/").split("/");
                    skillId = parts[parts.length > 1 ? parts.length - 2 : 0];
                    if ("SKILL.md".equalsIgnoreCase(skillId)) {
                        skillId = null;
                    }
                }
            }
            if (skillId != null) {
                return SKILL_LABELS.getOrDefault(skillId.toLowerCase(), skillId);
            }
        } catch (Exception e) {
            logger.debug("[PROGRESS] 解析 load_skill_through_path 参数失败: {}", e.getMessage());
        }
        return "加载技能";
    }

    /** 序列化工具入参为 JSON 字符串，超长截断。 */
    private String extractToolArguments(ToolUseBlock toolUse) {
        try {
            Object input = toolUse.getInput();
            if (input == null) {
                return null;
            }
            String json = (input instanceof String s) ? s : JSON.toJSONString(input);
            if (json == null || json.isBlank() || "{}".equals(json.trim())) {
                return null;
            }
            return json.length() > ARGUMENTS_TEXT_MAX_LENGTH
                    ? json.substring(0, ARGUMENTS_TEXT_MAX_LENGTH) + "...已截断"
                    : json;
        } catch (Exception e) {
            logger.debug("[PROGRESS] 序列化工具入参失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 提取 {@link ToolResultBlock} 中所有文本块并按顺序拼接。
     * 通过 JSON 序列化每个 ContentBlock 后取 {@code text} 字段，兼容不同子类型（TextBlock 等）
     * 且无需直接依赖 sealed 子类。
     *
     * @param maxLength 大于 0 时按字符数截断；小于等于 0 时不截断
     * @return 拼接后的文本，无有效内容时返回 {@code null}
     */
    private static String extractToolResultText(ToolResultBlock toolResult, int maxLength) {
        if (toolResult == null || toolResult.isSuspended() || toolResult.getOutput() == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Object block : toolResult.getOutput()) {
            try {
                JSONObject json = JSON.parseObject(JSON.toJSONString(block));
                String text = json.getString("text");
                if (text != null && !text.isBlank()) {
                    if (sb.length() > 0) {
                        sb.append("\n");
                    }
                    sb.append(text.trim());
                }
            } catch (Exception ignored) {
                // 序列化失败时跳过该块
            }
        }
        if (sb.length() == 0) {
            return null;
        }
        String result = sb.toString();
        if (maxLength > 0 && result.length() > maxLength) {
            return result.substring(0, maxLength) + "\n...已截断";
        }
        return result;
    }

    // ============ SSE 事件发送 ============

    private void sendProgress(SseEmitter emitter, Map<String, String> data) {
        // 进度事件可能带手机号、身份证、API Key 等敏感字段，出口统一脱敏
        writeMaskedEvent(emitter, "progress", data, "PROGRESS");
    }

    private void sendThinking(SseEmitter emitter, String agentName, String text) {
        Map<String, String> data = new HashMap<>();
        data.put("agentName", agentName);
        data.put("text", text);
        writeMaskedEvent(emitter, "thinking", data, "THINKING");
    }

    /**
     * 向前端发送"思考新一轮开始"信号，前端会在 {@code thinkingByAgent[agentName]} 数组中插入
     * 新的空字符串，实现多轮思考分割展示。
     */
    private void sendThinkingRoundStart(SseEmitter emitter, String agentName) {
        Map<String, String> data = new HashMap<>();
        data.put("agentName", agentName);
        data.put("roundStart", "true");
        writeMaskedEvent(emitter, "thinking", data, "THINKING");
    }

    /**
     * 序列化 → 脱敏 → 通过 emitter 发送。异常向上抛出，调用方决定日志与降级策略。
     */
    private void sendRawEvent(SseEmitter emitter, String eventName, Object data) throws IOException {
        String masked = SensitiveMasker.mask(JSON.toJSONString(data));
        emitter.send(SseEmitter.event().name(eventName).data(masked));
    }

    /**
     * 统一 SSE 出口：调用 {@link #sendRawEvent} 并吞掉常见的断连异常。
     *
     * <p>所有对前端可见的 progress / thinking 事件都应经过此方法，避免手机号、身份证、
     * 银行卡号、邮箱、API Key、KV 秘钥等敏感字段外泄。{@link SensitiveMasker} 使用带边界的
     * 正则匹配 + 值级脱敏策略，替换后的字符串仍是合法 JSON。</p>
     */
    private void writeMaskedEvent(SseEmitter emitter, String eventName, Object data, String logTag) {
        try {
            sendRawEvent(emitter, eventName, data);
        } catch (IllegalStateException e) {
            // emitter 已完成（客户端断开 / 超时），静默忽略
            logger.debug("[{}] 发送失败，emitter 已完成: {}", logTag, e.getMessage());
        } catch (IOException e) {
            logger.debug("[{}] 发送失败（网络异常）: {}", logTag, e.getMessage());
        }
    }

    // ============ PlanNotebook 计划变化推送 ============

    /**
     * 当 PlanNotebook 计划发生变化（创建/修改/子任务状态更新/完成）时，
     * 通过 SSE 向前端推送 {@code plan_update} 事件，包含当前任务列表及其状态。
     *
     * <p>事件 JSON 格式：
     * <pre>
     * {
     *   "type": "plan_update",
     *   "agentName": "ItineraryPlanAgent",
     *   "planName": "行程规划",
     *   "tasks": [
     *     { "idx": 0, "name": "搜索航班", "state": "done" },
     *     { "idx": 1, "name": "搜索酒店", "state": "in_progress" },
     *     { "idx": 2, "name": "预订机票", "state": "todo" }
     *   ]
     * }
     * </pre>
     */
    public void sendPlanUpdate(String sessionId, Plan plan) {
        if (sessionId == null) {
            return;
        }
        SseEmitter emitter = emitterRegistry.get(sessionId);
        if (emitter == null) {
            return;
        }

        Map<String, Object> data = new HashMap<>();
        data.put("type", "plan_update");
        data.put("agentName", "ItineraryPlanAgent");
        List<SubTask> subtasks = plan != null ? plan.getSubtasks() : null;
        if (plan != null) {
            data.put("planName", plan.getName() != null ? plan.getName() : "");
            data.put("tasks", buildPlanTasks(subtasks));
        } else {
            // 计划已完成或被清空
            data.put("planName", "");
            data.put("tasks", List.of());
        }

        try {
            // 计划/任务名理论上不含敏感字段，但仍走统一脱敏出口，防御性覆盖
            sendRawEvent(emitter, "plan_update", data);
            logger.debug("[PLAN_UPDATE] 已推送任务列表 sessionId={}, tasks={}",
                    sessionId, subtasks != null ? subtasks.size() : 0);
        } catch (IllegalStateException e) {
            logger.debug("[PLAN_UPDATE] 发送失败，emitter 已完成: {}", e.getMessage());
        } catch (IOException e) {
            logger.debug("[PLAN_UPDATE] 发送失败（网络异常）: {}", e.getMessage());
        }
    }

    private List<Map<String, Object>> buildPlanTasks(List<SubTask> subtasks) {
        if (subtasks == null || subtasks.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> tasks = new ArrayList<>(subtasks.size());
        for (int i = 0; i < subtasks.size(); i++) {
            SubTask st = subtasks.get(i);
            Map<String, Object> task = new HashMap<>();
            task.put("idx", i);
            task.put("name", st.getName() != null ? st.getName() : "");
            task.put("state", mapSubTaskState(st.getState()));
            tasks.add(task);
        }
        return tasks;
    }

    private String mapSubTaskState(SubTaskState state) {
        if (state == null) {
            return "todo";
        }
        return switch (state) {
            case TODO -> "todo";
            case IN_PROGRESS -> "in_progress";
            case DONE -> "done";
            case ABANDONED -> "abandoned";
        };
    }

    // ============ 旅行搜索结果识别与推送 ============

    /**
     * 尝试从工具执行结果中识别酒店 / 机票 / 火车票等搜索结果，通过 {@link TravelDataNormalizer}
     * 统一归一化为 {@code {type, items}} 格式，并向前端推送 {@code travel_data} 事件以卡片形式展示。
     * <p>已兼容：flyai、rolling-go-hotel、tuniu-cli、flight-manager 等技能输出。
     */
    private void tryEmitTravelData(SseEmitter emitter, ToolResultBlock toolResult, String toolName) {
        if (toolResult == null || toolResult.getOutput() == null) {
            return;
        }
        String fullText = extractToolResultText(toolResult, 0);
        if (fullText == null || fullText.isBlank()) {
            return;
        }

        try {
            JSONObject payload = travelDataNormalizer.normalize(fullText);
            if (payload == null || payload.isEmpty()) {
                logger.info("[TRAVEL_DATA] tool={} 未识别为可展示旅行数据，原始文本长度={}",
                        toolName, fullText.length());
                return;
            }

            // 最多只展示前 TRAVEL_DATA_MAX_ITEMS 条，避免卡片过长
            JSONArray items = payload.getJSONArray("items");
            if (items != null && items.size() > TRAVEL_DATA_MAX_ITEMS) {
                JSONArray trimmed = new JSONArray();
                for (int i = 0; i < TRAVEL_DATA_MAX_ITEMS; i++) {
                    trimmed.add(items.get(i));
                }
                payload.put("items", trimmed);
            }

            // 卡片里可能有联系人电话、证件号等敏感字段，走统一脱敏出口
            sendRawEvent(emitter, "travel_data", payload);
            logger.info("[TRAVEL_DATA] tool={} 已推送 {} 条 {} 搜索结果",
                    toolName, payload.getJSONArray("items").size(), payload.getString("type"));
        } catch (IllegalStateException e) {
            logger.debug("[TRAVEL_DATA] 发送失败，emitter 已完成: {}", e.getMessage());
        } catch (Exception e) {
            logger.warn("[TRAVEL_DATA] tool={} 解析或发送失败: {}", toolName, e.getMessage());
        }
    }

    // ============ 辅助 ============

    private String getAgentName(HookEvent event) {
        Agent agent = event.getAgent();
        return agent != null ? agent.getName() : "unknown";
    }

    /** 从 Memory 最新消息向前查找带 {@code sessionId} metadata 的消息，用于 Context 传播断裂时回退。 */
    private String extractSessionId(Memory memory) {
        if (memory == null) {
            return null;
        }
        List<Msg> messages = memory.getMessages();
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Map<String, Object> metadata = messages.get(i).getMetadata();
            if (metadata != null && metadata.get("sessionId") != null) {
                return metadata.get("sessionId").toString();
            }
        }
        return null;
    }
}
