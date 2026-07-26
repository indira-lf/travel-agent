package com.travel.agent;

import com.alibaba.fastjson2.JSON;
import com.travel.agent.common.PromptLoader;
import com.travel.agent.hook.ActiveAgentPersistenceHook;
import com.travel.agent.hook.AgentExecutionLoggerHook;
import com.travel.agent.hook.ProgressNotifierHook;
import com.travel.agent.hook.SessionPersistenceHook;
import com.travel.agent.intent.IntentRecognitionResult;
import com.travel.agent.intent.IntentRecognitionRouter;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.interruption.InterruptContext;
import io.agentscope.core.message.*;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 差旅对话意图识别 Agent（三层：L1 规则 → L2 向量 → L3 LLM）。
 *
 * <p>本类同时承担「工厂配置」与「Agent 实现」两个角色（原先拆分为
 * {@code IntentRecognitionAgent} 配置类 + {@code IntentRecognizer} 实现类，现已合并）：
 * <ul>
 *   <li>作为 {@code @Scope("prototype")} 的 Spring bean（bean 名 {@code intentRecognitionAgent}），
 *       每个 session 拿到独立实例，保证中断状态互不污染；</li>
 *   <li>继承 {@link AgentBase}，在 {@link #doCall} 中按 L1（规则/关键词）→ L2（向量相似度）
 *       → L3（LLM）短路：L1/L2 命中时直接返回与 LLM 输出同构的 JSON，完全绕过大模型，
 *       延迟稳定在 &lt;50ms / &lt;100ms；</li>
 *   <li>L3 兜底不需要包装 {@code ReActAgent}——由于意图识别是「单轮、无工具、maxIters=1」的一次性
 *       调用，直接使用 {@link Model#stream} 发起单次 LLM 请求即可，省去 ReActAgent 的循环与
 *       memory 开销（与 {@code QuestionRecommendationService} 同构）。</li>
 * </ul>
 *
 * <p>所有「针对意图识别」的 Hook（{@link SessionPersistenceHook}、
 * {@link AgentExecutionLoggerHook}、{@link ProgressNotifierHook}、
 * {@link ActiveAgentPersistenceHook}）直接挂在本实例上，System Hook 由 {@link AgentBase} 自动附加。</p>
 *
 * @author Hollis
 */
@Component("intentRecognitionAgent")
@Scope("prototype")
public class IntentRecognitionAgent extends AgentBase {

    private static final Logger logger = LoggerFactory.getLogger(IntentRecognitionAgent.class);

    public static final String NAME = "IntentRecognitionAgent";

    private final Model stableModel;
    private final IntentRecognitionRouter router;

    public IntentRecognitionAgent(
            @Qualifier("stableModel") Model stableModel,
            IntentRecognitionRouter router,
            AgentExecutionLoggerHook executionLoggerHook,
            ProgressNotifierHook progressNotifierHook,
            ActiveAgentPersistenceHook activeAgentPersistenceHook,
            SessionPersistenceHook sessionPersistenceHook) {
        super(NAME, "差旅对话意图识别与路由决策（L1/L2/L3 三层）", true,
                List.of(executionLoggerHook, progressNotifierHook, sessionPersistenceHook, activeAgentPersistenceHook));
        this.stableModel = stableModel;
        this.router = router;
    }

    @Override
    protected Mono<Msg> doCall(List<Msg> msgs) {
        String question = extractLatestUserText(msgs);
        if (question != null && !question.isBlank()) {
            Optional<IntentRecognitionResult> hit = router.route(question);
            if (hit.isPresent()) {
                IntentRecognitionResult result = hit.get();
                String json = JSON.toJSONString(result.toJsonMap());
                logger.debug("[INTENT_RECOGNITION] 命中快路径，source={} intent={} cost_layer_skip=true",
                        result.getSource(), result.getPrimaryIntent());
                Msg response = Msg.builder()
                        .name(NAME)
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text(json).build())
                        .build();
                return Mono.just(response);
            }
        }

        // L1/L2 未命中（或空输入），走 L3 LLM 兜底
        logger.debug("[INTENT_RECOGNITION] L1/L2 未命中，转交 L3 LLM 兜底");
        return callLlmFallback(msgs);
    }

    /**
     * L3 兜底：直接调用大模型完成一次意图识别。
     *
     * <p>意图识别无需工具调用、无需多轮推理，故不经过 ReActAgent，直接在系统提示词后拼接
     * 输入消息，通过 {@link Model#stream} 发起单次请求并聚合文本作为结果 JSON。</p>
     */
    private Mono<Msg> callLlmFallback(List<Msg> msgs) {
        List<Msg> messages = new ArrayList<>();
        messages.add(Msg.builder()
                .role(MsgRole.SYSTEM)
                .name("system")
                .content(TextBlock.builder().text(PromptLoader.load("intent-recognition-agent-system.md")).build())
                .build());
        if (msgs != null) {
            messages.addAll(msgs);
        }

        return Mono.fromCallable(() -> stableModel.stream(messages, null, null).collectList().block())
                .map(responses -> Msg.builder()
                        .name(NAME)
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text(extractText(responses)).build())
                        .build());
    }

    @Override
    protected Mono<Msg> handleInterrupt(InterruptContext context, Msg... originalArgs) {
        // 单轮 LLM 调用无内部循环可续跑，被中断时直接返回中文中断提示。
        return Mono.just(buildInterruptMsg());
    }

    @Override
    protected Mono<Void> doObserve(Msg msg) {
        // 无状态的 L1/L2/L3 一次性识别器，不需要观察记忆。
        return Mono.empty();
    }

    // ============== 工具方法 ==============

    /**
     * 从输入消息中提取最新一条 USER 消息的文本。
     */
    private static String extractLatestUserText(List<Msg> msgs) {
        if (msgs == null || msgs.isEmpty()) {
            return "";
        }
        for (int i = msgs.size() - 1; i >= 0; i--) {
            Msg m = msgs.get(i);
            if (m.getRole() == MsgRole.USER) {
                String t = m.getTextContent();
                if (t != null) {
                    return t.trim();
                }
            }
        }
        // 兜底：取最后一条消息
        String t = msgs.get(msgs.size() - 1).getTextContent();
        return t == null ? "" : t.trim();
    }

    /**
     * 将流式 {@link ChatResponse} 中的所有 TextBlock 文本按顺序拼接为完整字符串。
     */
    private static String extractText(List<ChatResponse> responses) {
        if (responses == null || responses.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ChatResponse response : responses) {
            List<ContentBlock> blocks = response.getContent();
            if (blocks == null) {
                continue;
            }
            for (ContentBlock block : blocks) {
                if (block instanceof TextBlock textBlock) {
                    sb.append(textBlock.getText());
                }
            }
        }
        return sb.toString();
    }

    private static Msg buildInterruptMsg() {
        return Msg.builder()
                .name(NAME)
                .role(MsgRole.ASSISTANT)
                .content(TextBlock.builder().text("已停止意图识别。").build())
                .generateReason(GenerateReason.INTERRUPTED)
                .build();
    }
}
