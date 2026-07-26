package com.gogo.travel.agent.intent;

import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.RetrieveConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * L2 向量相似度匹配器。
 *
 * <p>使用 {@code intentRouterKnowledge} 知识库（见
 * {@link com.gogo.travel.config.IntentRouterKnowledgeConfig}）执行 Top-1 检索。
 * 当最高相似度 &gt;= {@link #DEFAULT_SCORE_THRESHOLD} 时视为命中。embedding 计算与检索都走
 * {@link io.agentscope.core.embedding.dashscope.DashScopeTextEmbedding} 远程接口，
 * 因此 L2 仍然要走一次网络，但与 L3 完整 ReAct 推理相比成本和延迟都低一个量级。</p>
 *
 * <p>阈值默认 0.65，偏低以减少误判；当 {@code app.intent-recognition.vector-threshold} 配置后
 * 可调。命中后 LLM 兜底不再被调用，目标延迟 &lt; 100ms。</p>
 *
 * <p>本类 <b>不</b>使用 {@code @Component}，因为容器中存在多个 {@link Knowledge} bean
 * （RAG 景点/政策库 + 本意图路由库），构造函数参数自动注入会因类型不唯一而失败。
 * bean 注册在 {@link com.gogo.travel.config.IntentRouterKnowledgeConfig} 中完成。</p>
 *
 * @author Hollis
 */
public class IntentVectorMatcher {

    private static final Logger logger = LoggerFactory.getLogger(IntentVectorMatcher.class);

    /** 默认相似度阈值；可通过 application.yml 调整。 */
    public static final double DEFAULT_SCORE_THRESHOLD = 0.75;

    /** Top-K 检索上限；为了避免 L2 比 L3 还慢，这里只取 1。 */
    private static final int TOP_K = 1;

    private final Knowledge knowledge;

    private double scoreThreshold;

    public IntentVectorMatcher(Knowledge knowledge) {
        this(knowledge, DEFAULT_SCORE_THRESHOLD);
    }

    public IntentVectorMatcher(Knowledge knowledge, double scoreThreshold) {
        this.knowledge = knowledge;
        this.scoreThreshold = scoreThreshold;
    }

    /**
     * 设置相似度阈值，便于外部动态调整（未接入配置中心，保留扩展点）。
     */
    public void setScoreThreshold(double scoreThreshold) {
        if (scoreThreshold < 0.0 || scoreThreshold > 1.0) {
            throw new IllegalArgumentException("scoreThreshold must be in [0, 1]");
        }
        this.scoreThreshold = scoreThreshold;
    }

    public double getScoreThreshold() {
        return scoreThreshold;
    }

    /**
     * 对输入文本执行 L2 向量检索。
     *
     * @param text 改写后的问题文本（已 trim）
     * @return 命中时返回结果，未命中返回 {@link Optional#empty()}
     */
    public Optional<IntentRecognitionResult> match(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String normalized = text.trim();

        RetrieveConfig config = RetrieveConfig.builder()
                .limit(TOP_K)
                // 先取 top1，由我们自己做阈值过滤，方便拿到原始 score
                .scoreThreshold(0.0)
                .build();

        List<Document> docs;
        try {
            docs = knowledge.retrieve(normalized, config).block();
        } catch (Exception e) {
            // 任何 embedding/检索失败都视为未命中，让 L3 兜底；不阻塞主流程
            logger.warn("[INTENT_ROUTER] L2 向量检索失败，降级到 L3: {}", e.getMessage());
            return Optional.empty();
        }

        if (docs == null || docs.isEmpty()) {
            return Optional.empty();
        }

        Document top = docs.get(0);
        Double score = top.getScore();
        if (score == null || score < scoreThreshold) {
            logger.debug("[INTENT_ROUTER] L2 top-1 相似度 {} < 阈值 {}，放行到 L3", score, scoreThreshold);
            return Optional.empty();
        }

        Object intentCode = top.getPayloadValue("intent");
        if (intentCode == null) {
            return Optional.empty();
        }
        IntentCategory category = IntentCategory.fromCode(intentCode.toString());

        ConfidenceFromScore conf = classify(score);
        return Optional.of(IntentRecognitionResult.single(
                IntentRecognitionResult.Source.VECTOR,
                category,
                conf.confidence,
                "L2 向量命中：相似度=" + String.format("%.3f", score)
                        + "，匹配样本「" + top.getMetadata().getContentText() + "」",
                score));
    }

    private static ConfidenceFromScore classify(double score) {
        if (score >= 0.85) {
            return new ConfidenceFromScore(IntentRecognitionResult.Confidence.HIGH);
        }
        if (score >= 0.75) {
            return new ConfidenceFromScore(IntentRecognitionResult.Confidence.MEDIUM);
        }
        return new ConfidenceFromScore(IntentRecognitionResult.Confidence.LOW);
    }

    private record ConfidenceFromScore(IntentRecognitionResult.Confidence confidence) {}
}
