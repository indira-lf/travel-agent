package com.gogo.travel.agent.intent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 三层意图识别编排器。
 *
 * <p>按 L1 → L2 → L3 的顺序尝试：
 * <ol>
 *   <li>L1 规则/关键词匹配：目标延迟 &lt; 50ms；命中即短路；</li>
 *   <li>L2 向量相似度匹配：目标延迟 &lt; 100ms；命中即短路；</li>
 *   <li>L3 由调用方在返回 {@link Optional#empty()} 时委派给原 LLM 意图识别 Agent。</li>
 * </ol>
 *
 * <p>每次路由都会记录每层耗时，便于上线后通过日志观察命中率与延迟分布。</p>
 *
 * @author Hollis
 */
@Component
public class IntentRecognitionRouter {

    private static final Logger logger = LoggerFactory.getLogger(IntentRecognitionRouter.class);

    /** L1 软目标延迟（ms），仅用于日志告警。 */
    private static final long L1_TARGET_MS = 50;
    /** L2 软目标延迟（ms），仅用于日志告警。 */
    private static final long L2_TARGET_MS = 100;

    /** 日志中字符串截断最大长度 */
    private static final int LOG_TRUNCATE_LENGTH = 80;

    @Autowired
    private IntentRuleMatcher ruleMatcher;
    @Autowired
    private IntentVectorMatcher vectorMatcher;

    /**
     * 路由识别意图。
     *
     * @param question 改写后的问题文本（已 trim）
     * @return 命中 L1/L2 时返回结果；未命中返回 {@link Optional#empty()},由调用方兜底 L3
     */
    public Optional<IntentRecognitionResult> route(String question) {
        if (question == null || question.isBlank()) {
            return Optional.empty();
        }
        String normalized = question.trim();

        // -------- L1 --------
        long l1Start = System.nanoTime();
        Optional<IntentRecognitionResult> ruleHit = ruleMatcher.match(normalized);
        long l1Ms = (System.nanoTime() - l1Start) / 1_000_000L;
        if (ruleHit.isPresent()) {
            logResult("L1", l1Ms, ruleHit.get(), true);
            warnIfSlow("L1", l1Ms, L1_TARGET_MS);
            return ruleHit;
        }
        logger.debug("[INTENT_ROUTER] L1 miss, cost={}ms, fall through to L2", l1Ms);

        // -------- L2 --------
        long l2Start = System.nanoTime();
        Optional<IntentRecognitionResult> vectorHit;
        try {
            vectorHit = vectorMatcher.match(normalized);
        } catch (Exception e) {
            vectorHit = Optional.empty();
            logger.warn("[INTENT_ROUTER] L2 异常，降级到 L3: {}", e.getMessage());
        }
        long l2Ms = (System.nanoTime() - l2Start) / 1_000_000L;
        if (vectorHit.isPresent()) {
            logResult("L2", l2Ms, vectorHit.get(), true);
            warnIfSlow("L2", l2Ms, L2_TARGET_MS);
            return vectorHit;
        }
        logger.info("[INTENT_ROUTER] L1/L2 miss, will fall through to L3 (q='{}', L1={}ms, L2={}ms)",
                truncate(normalized), l1Ms, l2Ms);
        return Optional.empty();
    }

    private static void logResult(String layer, long costMs,
                                  IntentRecognitionResult result, boolean hit) {
        logger.info("[INTENT_ROUTER] {} {} cost={}ms intent={} confidence={} reason={}",
                layer,
                hit ? "HIT" : "MISS",
                costMs,
                result.getPrimaryIntent(),
                result.getIntents().get(0).getConfidence().wireValue(),
                result.getOverallReason());
    }

    private static void warnIfSlow(String layer, long costMs, long targetMs) {
        if (costMs > targetMs) {
            logger.warn("[INTENT_ROUTER] {} 命中但耗时 {}ms 超过目标 {}ms，请关注 embedding/规则性能",
                    layer, costMs, targetMs);
        }
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= LOG_TRUNCATE_LENGTH ? s : s.substring(0, LOG_TRUNCATE_LENGTH) + "...";
    }
}
