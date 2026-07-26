package com.travel.agent.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.travel.agent.IntentRecognitionAgent;
import com.travel.agent.QueryRewritingAgent;
import com.travel.agent.hook.SessionPersistenceHook;
import com.travel.agent.intent.IntentRecognitionResult;
import com.travel.agent.intent.IntentRecognitionRouter;
import com.travel.agent.registry.AgentExecutionRegistry;
import com.travel.agent.registry.AgentRegistry;
import com.travel.agent.tools.UserInteractionTools;
import com.travel.business.chat.service.ConversationTitleService;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.*;
import io.agentscope.core.session.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.context.Context;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 编排差旅 Agent 调用流水线，把「问题改写」与「意图识别」集成为一条条件流水线：
 *
 * <ol>
 *   <li>先用<b>原始问题</b>做 L1/L2 快速意图识别（{@link IntentRecognitionRouter}，不触发 L3 LLM）；</li>
 *   <li>若 L1/L2 命中：无需问题改写，直接进入调度；</li>
 *   <li>若 L1/L2 未命中：执行问题改写（QueryRewritingAgent），再对改写结果重新走完整
 *       L1/L2/L3（IntentRecognitionAgent），随后进入调度；</li>
 *   <li>调度决策：若意图为 <b>单意图 + 高置信度 + 映射到具体子智能体</b>，则直接调用对应子智能体、
 *       跳过 MasterAgent；否则走 MasterAgent 路由。</li>
 * </ol>
 *
 * <p>把问题改写做成「仅在 L1/L2 未命中时才触发」的条件步骤，可以让高频、表达清晰的请求
 * 完全省去一次改写 LLM 调用，降低延迟与成本；只有真正存在指代歧义、需要上下文补全的请求
 * 才付出改写代价。</p>
 *
 * <p>直跳的收益：MasterAgent 本身是一个 LLM 驱动的 ReAct Agent，如果意图明确，
 * 额外经过一次 LLM 推理会引入 1-3 秒额外延迟与一次模型调用费用；单意图直跳
 * 可以在不损失用户体验的前提下节省 100% 的 MasterAgent 推理成本。</p>
 *
 * @author Hollis
 */
@Service
public class AgentPipelineService {

    private static final Logger logger = LoggerFactory.getLogger(AgentPipelineService.class);

    private static final String MASTER_AGENT_NAME = "MasterAgent";

    private static final String INTENT_RECOGNITION_AGENT_NAME = "IntentRecognitionAgent";

    private static final String QUERY_REWRITING_AGENT_NAME = "QueryRewritingAgent";

    public static final Set<String> PIPELINE_AGENT_NAMES = Set.of(QUERY_REWRITING_AGENT_NAME, INTENT_RECOGNITION_AGENT_NAME, MASTER_AGENT_NAME);

    /**
     * 可直跳的子智能体
     */
    private static final Set<String> DISPATCHABLE_BEAN_NAMES = Set.of("infoAgent");

    /**
     * AgentScope 默认的英文中断恢复提示文本。
     */
    private static final String DEFAULT_INTERRUPT_RECOVERY_TEXT =
            "I noticed that you have interrupted me. What can I do for you?";

    /**
     * 被中断后返回给前端的中文提示文本。
     */
    private static final String CHINESE_INTERRUPT_RECOVERY_TEXT =
            "已停止生成。请告诉我接下来有什么可以帮您的？";

    @Autowired
    private AgentRegistry agentRegistry;

    @Autowired
    private AgentExecutionRegistry executionRegistry;

    @Autowired
    private Session session;

    @Autowired
    private ConversationTitleService conversationTitleService;

    @Autowired
    private IntentRecognitionRouter intentRecognitionRouter;

    @Autowired
    private SessionPersistenceHook sessionPersistenceHook;

    public Mono<Msg> executePipeline(List<Msg> inputMessages, String sessionId, String userId, String activeAgentName) {
        if (activeAgentName == null) {
            return executeFullPipeline(inputMessages, sessionId, userId);
        }
        if (MASTER_AGENT_NAME.equals(activeAgentName)) {
            // 明确继续/确认信号：直接续跑 MasterAgent，不做意图重识别
            return executeMasterAgentDirectly(inputMessages, sessionId, userId);
        } else if (INTENT_RECOGNITION_AGENT_NAME.equals(activeAgentName)) {
            // 对于 MasterAgent 等需要重新识别意图的 Agent，走从意图识别开始的流水线
            return executeFromIntentRecognition(inputMessages, sessionId, userId);
        } else {
            // 其他情况：直接续跑完整流水线
            return executeFullPipeline(inputMessages, sessionId, userId);
        }
    }

    /**
     * 执行完整流水线。
     *
     * @param inputMessages 用户原始消息列表
     * @param sessionId     对话会话 ID
     * @param userId        已登录用户 ID（由 sa-token 从 Redis 读取）
     * @return MasterAgent 的最终回复
     */
    public Mono<Msg> executeFullPipeline(List<Msg> inputMessages, String sessionId, String userId) {
        String originalQuestion = extractLatestUserText(inputMessages);

        // 第一步：先用原始问题做 L1/L2 快速意图识别（不触发 L3 LLM）。
        return Mono.fromCallable(() -> intentRecognitionRouter.route(originalQuestion))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(fastHit -> {
                    if (fastHit.isPresent()) {
                        // L1/L2 命中：无需问题改写，直接进入后续调度。
                        String intentJson = JSON.toJSONString(fastHit.get().toJsonMap());
                        logger.info("[PIPELINE] L1/L2 命中，跳过问题改写，直接调度: {}", intentJson);
                        // 快路径绕过了 QueryRewritingAgent 的实际 call，需合成一条影子历史写回 Session，
                        // 以免下一轮未命中 L1/L2 时 QueryRewritingAgent 看不到本轮上下文、无法消除指代。
                        appendQueryRewritingShadowHistory(sessionId, originalQuestion);
                        conversationTitleService.updateTitleAsync(sessionId, userId, originalQuestion, intentJson);
                        return dispatchByIntent(intentJson, inputMessages, originalQuestion, sessionId, userId);
                    }
                    // L1/L2 未命中：先做问题改写，再重新走 L1/L2/L3 及后续流程。
                    logger.info("[PIPELINE] L1/L2 未命中，进入问题改写后重新识别流程");
                    return rewriteThenRecognizeAndDispatch(inputMessages, sessionId, userId);
                })
                .doFinally(signal -> executionRegistry.remove(sessionId))
                .contextWrite(Context.of("sessionId", sessionId, "userId", userId));
    }

    /**
     * L1/L2 未命中时的降级流程：问题改写（QueryRewritingAgent）→ 重新意图识别
     * （IntentRecognitionAgent，此时走完整 L1/L2/L3）→ 调度。
     */
    private Mono<Msg> rewriteThenRecognizeAndDispatch(List<Msg> inputMessages, String sessionId, String userId) {
        QueryRewritingAgent queryRewritingAgent = agentRegistry.getQueryRewriter();
        IntentRecognitionAgent intentRecognitionAgent = agentRegistry.getIntentRecognizer();

        return queryRewritingAgent.call(inputMessages)
                .doOnSubscribe(s -> executionRegistry.register(sessionId, queryRewritingAgent))
                .flatMap(rewriteResult -> {
                    if (isInterruptRecovery(rewriteResult)) {
                        logger.info("[PIPELINE] QueryRewritingAgent 被优雅中断，终止流水线并返回中文提示");
                        return Mono.just(buildInterruptRecoveryMsg());
                    }
                    String rewrittenQuestion = parseRewrittenQuestion(rewriteResult.getTextContent());
                    logger.info("[PIPELINE] 问题改写完成: {}", rewrittenQuestion);
                    return intentRecognitionAgent.call(buildIntentInput(rewrittenQuestion))
                            .doOnSubscribe(s -> executionRegistry.register(sessionId, intentRecognitionAgent))
                            .flatMap(intentResult -> {
                                if (isInterruptRecovery(intentResult)) {
                                    logger.info("[PIPELINE] IntentRecognitionAgent 被优雅中断，终止流水线并返回中文提示");
                                    return Mono.just(buildInterruptRecoveryMsg());
                                }
                                String intentJson = intentResult.getTextContent();
                                logger.info("[PIPELINE] 意图识别完成: {}", intentJson);
                                conversationTitleService.updateTitleAsync(sessionId, userId, rewrittenQuestion, intentJson);
                                return dispatchByIntent(intentJson, inputMessages, rewrittenQuestion, sessionId, userId);
                            });
                });
    }

    /**
     * 根据意图识别结果进行调度：满足单意图 + 高置信度 + 命中子智能体白名单时直跳子智能体，
     * 否则走 MasterAgent 路由。
     *
     * @param intentJson      意图识别结果 JSON
     * @param inputMessages   用户原始消息列表
     * @param contextQuestion 供下游 Agent 使用的上下文问题（改写后的问题或原始问题）
     */
    private Mono<Msg> dispatchByIntent(String intentJson, List<Msg> inputMessages,
                                       String contextQuestion, String sessionId, String userId) {
        Optional<DirectDispatchPlan> planOpt = tryPlanDirectDispatch(intentJson);
        if (planOpt.isPresent()) {
            DirectDispatchPlan plan = planOpt.get();
            logger.info("[PIPELINE] 单意图高置信直跳：{} (intent={}, confidence={})，跳过 MasterAgent",
                    plan.beanName(), plan.intentCode(), plan.confidence());
            return dispatchSubAgentDirectly(plan, inputMessages, contextQuestion, sessionId, userId);
        }

        ReActAgent masterAgent = agentRegistry.getAgent(MASTER_AGENT_NAME);
        return masterAgent.call(buildMasterInput(inputMessages, contextQuestion, intentJson))
                .doOnSubscribe(s -> executionRegistry.register(sessionId, masterAgent))
                .flatMap(masterResult -> handleMasterResult(masterResult, sessionId));
    }

    /**
     * 统一处理 MasterAgent 的返回：中断恢复 / 等待用户输入 / 正常结果。
     */
    private Mono<Msg> handleMasterResult(Msg masterResult, String sessionId) {
        if (isInterruptRecovery(masterResult)) {
            logger.info("[PIPELINE] MasterAgent 被优雅中断，终止流水线并返回中文提示");
            return Mono.just(buildInterruptRecoveryMsg());
        }
        if (masterResult.getGenerateReason() == GenerateReason.TOOL_SUSPENDED) {
            masterResult.getContentBlocks(ToolUseBlock.class).stream()
                    .filter(t -> UserInteractionTools.TOOL_NAME.equals(t.getName()))
                    .findFirst()
                    .ifPresent(toolUse ->
                            logger.info("[PIPELINE] MasterAgent 进入等待用户输入状态, sessionId={}, toolUseId={}",
                                    sessionId, toolUse.getId()));
        }
        return Mono.just(masterResult);
    }

    /**
     * 从意图识别节点开始执行流水线（跳过 QueryRewritingAgent）。
     *
     * <p>适用于 continuation 场景：当会话已经命中 activeAgent 但希望重新做意图识别时，
     * 直接调用 IntentRecognitionAgent → MasterAgent，避免重复改写。</p>
     *
     * @param inputMessages 用户原始消息列表
     * @param sessionId     对话会话 ID
     * @param userId        已登录用户 ID
     * @return MasterAgent 的最终回复
     */
    public Mono<Msg> executeFromIntentRecognition(List<Msg> inputMessages, String sessionId, String userId) {
        IntentRecognitionAgent intentRecognitionAgent = agentRegistry.getIntentRecognizer();

        String userQuestion = extractLatestUserText(inputMessages);

        return intentRecognitionAgent.call(inputMessages)
                .doOnSubscribe(s -> executionRegistry.register(sessionId, intentRecognitionAgent))
                .flatMap(intentResult -> {
                    if (isInterruptRecovery(intentResult)) {
                        logger.info("[PIPELINE] IntentRecognitionAgent 被优雅中断，终止流水线并返回中文提示");
                        return Mono.just(buildInterruptRecoveryMsg());
                    }
                    String intentJson = intentResult.getTextContent();
                    logger.info("[PIPELINE] 意图识别完成: {}", intentJson);
                    conversationTitleService.updateTitleAsync(sessionId, userId, userQuestion, intentJson);
                    return dispatchByIntent(intentJson, inputMessages, userQuestion, sessionId, userId);
                })
                .doFinally(signal -> executionRegistry.remove(sessionId))
                .contextWrite(Context.of("sessionId", sessionId, "userId", userId));
    }

    /**
     * 直接执行 MasterAgent，跳过查询改写和意图识别。
     *
     * <p>适用于明确的 continuation 场景：用户只是对 MasterAgent 的待确认内容做简单回应
     * （如“确认”、“继续”），不需要重新识别意图。</p>
     *
     * @param inputMessages 用户原始消息列表
     * @param sessionId     对话会话 ID
     * @param userId        已登录用户 ID
     * @return MasterAgent 的最终回复
     */
    public Mono<Msg> executeMasterAgentDirectly(List<Msg> inputMessages, String sessionId, String userId) {
        ReActAgent masterAgent = agentRegistry.getAgent(MASTER_AGENT_NAME);

        return masterAgent.call(inputMessages)
                .doOnSubscribe(s -> executionRegistry.register(sessionId, masterAgent))
                .flatMap(masterResult -> {
                    if (isInterruptRecovery(masterResult)) {
                        logger.info("[PIPELINE] MasterAgent 被优雅中断，终止流水线并返回中文提示");
//                        persistInterruptedMemory(masterAgent, sessionId);
                        return Mono.just(buildInterruptRecoveryMsg());
                    }
                    if (masterResult.getGenerateReason() == GenerateReason.TOOL_SUSPENDED) {
                        masterResult.getContentBlocks(ToolUseBlock.class).stream()
                                .filter(t -> UserInteractionTools.TOOL_NAME.equals(t.getName()))
                                .findFirst()
                                .ifPresent(toolUse ->
                                        logger.info("[PIPELINE] MasterAgent 进入等待用户输入状态, sessionId={}, toolUseId={}", sessionId, toolUse.getId())
                                );
                    }
                    return Mono.just(masterResult);
                })
                .doFinally(signal -> executionRegistry.remove(sessionId))
                .contextWrite(Context.of("sessionId", sessionId, "userId", userId));
    }

    /**
     * 从消息列表中提取最新一条用户消息的文本内容。
     */
    private String extractLatestUserText(List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Msg msg = messages.get(i);
            if (msg.getRole() == MsgRole.USER) {
                String text = msg.getTextContent();
                return text != null ? text.trim() : "";
            }
        }
        String fallback = messages.get(messages.size() - 1).getTextContent();
        return fallback != null ? fallback.trim() : "";
    }

    /**
     * 从 QueryRewritingAgent 的输出中提取 rewritten_question。
     * 如果解析失败，回退到使用原始文本。
     */
    private String parseRewrittenQuestion(String text) {
        try {
            String json = extractJsonBlock(text);
            if (json.isBlank()) {
                return text != null ? text.trim() : "";
            }
            JSONObject obj = JSON.parseObject(json);
            String rewritten = obj.getString("rewritten_question");
            if (rewritten != null && !rewritten.isBlank()) {
                return rewritten.trim();
            }
        } catch (Exception e) {
            logger.warn("[PIPELINE] 解析改写结果失败，使用原始文本: {}", e.getMessage());
        }
        return text != null ? text.trim() : "";
    }

    /**
     * 从可能包含 markdown 代码块的文本中提取 JSON 对象字符串。
     */
    private String extractJsonBlock(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            int start = trimmed.indexOf('{');
            int end = trimmed.lastIndexOf('}');
            if (start >= 0 && end > start) {
                return trimmed.substring(start, end + 1);
            }
        }
        return trimmed;
    }

    /**
     * 构建 IntentRecognitionAgent 的输入：只传入改写后的问题。
     */
    private List<Msg> buildIntentInput(String rewrittenQuestion) {
        Msg msg = Msg.builder()
                .role(MsgRole.USER)
                .name("user")
                .content(TextBlock.builder().text(rewrittenQuestion).build())
                .build();
        return List.of(msg);
    }

    /**
     * 构建 MasterAgent 的输入：在原始用户消息前附加改写结果和意图识别结果。
     */
    private List<Msg> buildMasterInput(List<Msg> originalMessages, String rewrittenQuestion, String intentJson) {
        List<Msg> messages = new ArrayList<>();

        Msg rewriteContext = Msg.builder()
                .role(MsgRole.SYSTEM)
                .name("system")
                .content(TextBlock.builder().text("问题改写结果：\n" + rewrittenQuestion).build())
                .build();

        Msg intentContext = Msg.builder()
                .role(MsgRole.SYSTEM)
                .name("system")
                .content(TextBlock.builder().text("意图识别结果：\n" + intentJson).build())
                .build();

        messages.add(rewriteContext);
        messages.add(intentContext);
        messages.addAll(originalMessages);
        return messages;
    }

    /**
     * 持久化被中断 Agent 的当前对话记忆。
     *
     * <p>AgentScope 的优雅中断路径通过 {@code onErrorResume} 直接返回恢复消息，
     * 不会触发 {@code PostCallEvent}，因此 {@link SessionPersistenceHook} 无法自动保存。
     * 这里在流水线检测到中断恢复消息后，手动把 memory_messages 写入 Session，
     * 保证被中断轮次的用户输入和恢复提示不会丢失。</p>
     */
    private void persistInterruptedMemory(ReActAgent agent, String sessionId) {
        try {
            String agentName = agent.getName();
            String sessionKey = sessionId + ":" + agentName;
            Memory memory = agent.getMemory();
            memory.saveTo(session, sessionKey);
            logger.debug("[SessionPersistence] 已保存被中断 Agent 的对话记忆: sessionId={}, agent={}", sessionId, agentName);
        } catch (Exception e) {
            logger.warn("[SessionPersistence] 保存被中断 Agent 的对话记忆失败 sessionId={}: {}", sessionId, e.getMessage());
        }
    }

    /**
     * 判断 Agent 返回结果是否为优雅中断恢复提示。
     *
     * <p>AgentScope 被中断后会返回固定英文恢复文本，且当前版本未在 Msg 中标记
     * {@link GenerateReason#INTERRUPTED}，因此需要同时识别文本内容作为兜底。
     */
    private boolean isInterruptRecovery(Msg result) {
        if (result == null) {
            return false;
        }
        if (result.getGenerateReason() == GenerateReason.INTERRUPTED) {
            return true;
        }
        String text = result.getTextContent();
        return text != null && text.trim().equals(DEFAULT_INTERRUPT_RECOVERY_TEXT);
    }

    /**
     * 构建中文中断恢复提示消息。
     */
    private Msg buildInterruptRecoveryMsg() {
        return Msg.builder()
                .role(MsgRole.ASSISTANT)
                .name("GoGo助手")
                .content(TextBlock.builder().text(CHINESE_INTERRUPT_RECOVERY_TEXT).build())
                .generateReason(GenerateReason.INTERRUPTED)
                .build();
    }

    // ============== 单意图直跳子智能体支持 ==============

    /**
     * 单意图直跳计划。包含调用子智能体所需的全部信息。
     *
     * @param beanName    Spring 容器中可 {@code context.getBean(name, ReActAgent.class)} 拉取的 bean 名
     * @param intentCode  primary intent code（如 {@code "policy_query"}），用于日志
     * @param confidence  primary intent confidence（如 {@code "high"} / {@code "medium"} / {@code "low"}）
     * @param targetAgent 原意图 JSON 中的 target_agent（PascalCase），仅用于日志
     */
    private record DirectDispatchPlan(String beanName, String intentCode, String confidence, String targetAgent) {
    }

    /**
     * 解析意图识别结果，判断是否可以跳过 MasterAgent 直接调度具体子智能体。
     *
     * <p>判定条件（全部满足才返回 plan，否则降级到 MasterAgent）：
     * <ol>
     *   <li>JSON 可解析、{@code multi_intent=false}、{@code intents.length==1}；</li>
     *   <li>LLM 输出的 {@code target_agent} 是合法的 Spring bean 名（4 个子智能体之一，排除 masterAgent）；</li>
     *   <li>置信度为 {@code "high"}：中/低置信度保守起见仍走 MasterAgent 进行二次确认，避免直跳错误路由。</li>
     * </ol>
     */
    private Optional<DirectDispatchPlan> tryPlanDirectDispatch(String intentJson) {
        if (intentJson == null || intentJson.isBlank()) {
            return Optional.empty();
        }
        try {
            String json = extractJsonBlock(intentJson);
            JSONObject obj = JSON.parseObject(json);
            if (obj == null) {
                return Optional.empty();
            }
            Boolean multi = obj.getBoolean("multi_intent");
            if (Boolean.TRUE.equals(multi)) {
                logger.debug("[PIPELINE] multi_intent=true，不走直跳");
                return Optional.empty();
            }
            JSONArray intents = obj.getJSONArray("intents");
            if (intents == null || intents.size() != 1) {
                return Optional.empty();
            }
            JSONObject primary = intents.getJSONObject(0);
            if (primary == null) {
                return Optional.empty();
            }
            String targetAgent = primary.getString("target_agent");
            String confidence = primary.getString("confidence");
            String intentCode = primary.getString("intent");

            // 保守策略：仅高置信度才直跳，中/低置信度一律走 MasterAgent 二次确认。
            // LLM 输出的 "low" 在子智能体路由上仍然可能召错 Agent（例如把行程规划误识为机票查询），
            // 多一次 LLM 推理的代价远低于一次错误路由。
            if (!IntentRecognitionResult.Confidence.HIGH.name().equalsIgnoreCase(confidence)) {
                logger.info("[PIPELINE] 单意图 confidence={} 非 high，保守起见仍走 MasterAgent (intent={})",
                        confidence, intentCode);
                return Optional.empty();
            }

            // LLM 输出的 target_agent 必须直接命中 4 个子智能体 bean 之一。
            // 之所以用白名单而不是 containsBean，是因为 masterAgent 也是 ReActAgent bean，
            // 单纯 containsBean 会把"跳过 MasterAgent 路由到 MasterAgent"这种退化路径也放行。
            if (targetAgent == null || !DISPATCHABLE_BEAN_NAMES.contains(Character.toLowerCase(targetAgent.charAt(0)) + targetAgent.substring(1))) {
                logger.info("[PIPELINE] target_agent={} 不可直跳（不在子智能体白名单中），走 MasterAgent", targetAgent);
                return Optional.empty();
            }

            return Optional.of(new DirectDispatchPlan(targetAgent, intentCode, confidence, targetAgent));
        } catch (Exception e) {
            logger.warn("[PIPELINE] 解析意图 JSON 失败，降级到 MasterAgent: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 按计划直接调用具体子智能体，跳过 MasterAgent。
     *
     * <p>本方法复用了子智能体上原有的 hooks（Progress / SessionPersistence /
     * ActiveAgentPersistence / ToolCircuitBreaker），因此：
     * <ul>
     *   <li>中断语义与原逻辑一致（{@link AgentExecutionRegistry} 注册/注销）；</li>
     *   <li>多轮对话记忆由子智能体自己的 {@code SessionPersistenceHook} 处理；</li>
     *   <li>进度事件、Human-in-the-Loop 提示等由子智能体自己触发。</li>
     * </ul>
     */
    private Mono<Msg> dispatchSubAgentDirectly(DirectDispatchPlan plan, List<Msg> originalMessages,
                                               String rewrittenQuestion, String sessionId, String userId) {
        final ReActAgent subAgent;
        try {
            subAgent = agentRegistry.getAgent(plan.beanName());
        } catch (Exception e) {
            logger.error("[PIPELINE] 直跳获取子智能体 bean={} 失败，降级到 MasterAgent: {}",
                    plan.beanName(), e.getMessage());
            return executeMasterAgentDirectly(originalMessages, sessionId, userId);
        }

        List<Msg> subAgentInput = buildSubAgentInput(originalMessages, rewrittenQuestion);
        return subAgent.call(subAgentInput)
                .doOnSubscribe(s -> executionRegistry.register(sessionId, subAgent))
                .flatMap(result -> {
                    if (isInterruptRecovery(result)) {
                        logger.info("[PIPELINE] 直跳 {} 被优雅中断，终止流水线并返回中文提示", plan.beanName());
                        return Mono.just(buildInterruptRecoveryMsg());
                    }
                    if (result.getGenerateReason() == GenerateReason.TOOL_SUSPENDED) {
                        result.getContentBlocks(ToolUseBlock.class).stream()
                                .filter(t -> UserInteractionTools.TOOL_NAME.equals(t.getName()))
                                .findFirst()
                                .ifPresent(toolUse -> logger.info(
                                        "[PIPELINE] 直跳 {} 进入等待用户输入状态, sessionId={}, toolUseId={}",
                                        plan.beanName(), sessionId, toolUse.getId()));
                    }
                    return Mono.just(result);
                })
                // 直跳路径不需要 doFinally(remove(sessionId))：调用方的外层 Mono 会负责清理
                .contextWrite(Context.of("sessionId", sessionId, "userId", userId));
    }

    /**
     * 为 QueryRewritingAgent 合成一对影子历史并写回 Session。
     *
     * <p>当 L1/L2 fast path 命中时，QueryRewritingAgent 本轮不会实际被 {@code call()}，
     * PostCall 不触发，导致 Session key {@code sessionId:QueryRewritingAgent} 始终为空。
     * 下一轮如果未命中 L1/L2 而进入改写流程，QueryRewritingAgent 就看不到任何
     * 上下文，无法完成指代消除与信息融合。
     *
     * <p>因为快路径下"改写"的语义等价于"不需要改写"，这里合成的 assistant 回复
     * 使用与 {@code query-rewriting-agent-system.md} 中定义的输出契约完全一致的 JSON（
     * {@code related=false}，{@code rewritten_question} 为原问题），以免下一轮 few-shot 中
     * 出现不合格式形式干扰模型。
     */
    private void appendQueryRewritingShadowHistory(String sessionId, String originalQuestion) {
        if (originalQuestion == null || originalQuestion.isBlank()) {
            return;
        }
        Msg userMsg = Msg.builder()
                .role(MsgRole.USER)
                .name("user")
                .content(TextBlock.builder().text(originalQuestion).build())
                .build();

        JSONObject payload = new JSONObject();
        payload.put("related", false);
        payload.put("rewritten_question", originalQuestion);
        payload.put("reason", "L1/L2 快路径命中，本轮未触发改写");
        Msg assistantMsg = Msg.builder()
                .role(MsgRole.ASSISTANT)
                .name(QueryRewritingAgent.NAME)
                .content(TextBlock.builder().text(payload.toJSONString()).build())
                .build();

        sessionPersistenceHook.appendStatelessHistory(sessionId, QueryRewritingAgent.NAME, userMsg, assistantMsg);
    }

    /**
     * 为子智能体构建输入消息。
     *
     * <p>子智能体都有自己独立的 system prompt（{@code info-agent-system.md} 等），
     * 不需要再像 MasterAgent 那样把 intent JSON 作为 SYSTEM 上下文传入——子智能体
     * 不知道也不需要知道有这个 JSON。
     *
     * <p>这里仅在多轮上下文中插入"改写后的问题"作为轻量上下文，避免将原始 user message
     * （可能含”它””那个”等指代）直接嗂给子智能体造成歧义。</p>
     */
    private List<Msg> buildSubAgentInput(List<Msg> originalMessages, String rewrittenQuestion) {
        List<Msg> messages = new ArrayList<>();
        if (rewrittenQuestion != null && !rewrittenQuestion.isBlank()) {
            messages.add(Msg.builder()
                    .role(MsgRole.SYSTEM)
                    .name("system")
                    .content(TextBlock.builder().text("改写后的问题：\n" + rewrittenQuestion).build())
                    .build());
        }
        if (originalMessages != null) {
            messages.addAll(originalMessages);
        }
        return messages;
    }
}
