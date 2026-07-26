package com.travel.agent.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 往返行程规划结果的 Redis 存储层。
 *
 * <p>封装「写入规划结果」与「读取规划结果」两个操作，供 {@link ItineraryPlannerTool}（写入方）
 * 和 {@link ItineraryReviewTools}（读取方）共用，保证 Key 格式与 TTL 策略统一。</p>
 *
 * <p>Redis Key 格式：{@code planner:result:{userId}}，按用户隔离。</p>
 * <p>TTL：24 小时，规划结果属于短期中间数据，超时自动清理。</p>
 *
 * @author Hollis
 */
@Component
public class ItineraryPlanStore {

    private static final Logger logger = LoggerFactory.getLogger(ItineraryPlanStore.class);

    /** Redis Key 前缀。 */
    private static final String KEY_PREFIX = "planner:result:";

    /** 规划结果过期时间：24 小时。 */
    private static final Duration RESULT_TTL = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;

    public ItineraryPlanStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 计算按用户隔离的 Redis Key。
     * userId 为空时归入 "default"；对 userId 做安全字符过滤。
     */
    public static String keyOf(String userId) {
        String uid = (userId == null || userId.trim().isEmpty())
                ? "default"
                : userId.trim().replaceAll("[^a-zA-Z0-9_.-]", "_");
        return KEY_PREFIX + uid;
    }

    /**
     * 将规划结果 JSON 写入 Redis。
     *
     * @param userId     当前用户 ID
     * @param resultJson 规划结果 JSON 字符串
     */
    public void save(String userId, String resultJson) {
        String key = keyOf(userId);
        redisTemplate.opsForValue().set(key, resultJson, RESULT_TTL);
        logger.debug("[ItineraryPlanStore] 已保存规划结果: key={}", key);
    }

    /**
     * 从 Redis 读取规划结果 JSON。
     *
     * @param userId 当前用户 ID
     * @return 规划结果 JSON 字符串；不存在或已过期时返回 null
     */
    public String load(String userId) {
        String key = keyOf(userId);
        String content = redisTemplate.opsForValue().get(key);
        if (content == null || content.isBlank()) {
            logger.debug("[ItineraryPlanStore] Redis 中无规划结果: key={}", key);
            return null;
        }
        return content;
    }
}
