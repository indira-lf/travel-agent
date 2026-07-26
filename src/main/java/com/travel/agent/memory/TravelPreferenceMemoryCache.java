package com.travel.agent.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.time.Duration;

/**
 * 旅行偏好长期记忆的 Redis 缓存层。
 *
 * <p>百炼长期记忆的 {@code retrieve} 属于跨网络的慢调用，且同一用户在一段时间内
 * 的偏好画像相对稳定。本组件以「用户维度」缓存 retrieve 的召回结果，Key 为
 * {@code ltm:travel-pref:{userId}}，命中缓存时直接返回，避免重复调用百炼。</p>
 *
 * <p>数据一致性：当用户偏好被 {@code record} 更新成功后，需调用 {@link #evict(String)}
 * 主动失效缓存；同时设置 TTL 作为兜底，防止外部更新导致的长期陈旧。</p>
 *
 * @author Hollis
 */
@Component
public class TravelPreferenceMemoryCache {

    private static final Logger logger = LoggerFactory.getLogger(TravelPreferenceMemoryCache.class);

    private static final String KEY_PREFIX = "ltm:travel-pref:";

    @Autowired
    private StringRedisTemplate redisTemplate;

    /** 缓存有效期（秒），默认 30 分钟。可通过 {@code travel.memory.cache.ttl-seconds} 覆盖。 */
    @Value("${travel.memory.cache.ttl-seconds:1800}")
    private long ttlSeconds;
    private Duration ttl;

    @PostConstruct
    public void init() {
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    /**
     * 读取指定用户的偏好召回缓存。
     *
     * @return 命中返回缓存内容；未命中或异常返回 {@code null}
     */
    public String get(String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        try {
            String cached = redisTemplate.opsForValue().get(KEY_PREFIX + userId);
            if (cached != null) {
                logger.debug("[TravelPreferenceCache] 命中 Redis 偏好缓存, userId={}", userId);
            }
            return cached;
        } catch (Exception e) {
            logger.warn("[TravelPreferenceCache] 读取偏好缓存失败, userId={}: {}", userId, e.getMessage());
            return null;
        }
    }

    /**
     * 写入指定用户的偏好召回缓存（带 TTL）。空内容不缓存。
     */
    public void put(String userId, String value) {
        if (userId == null || userId.isBlank() || value == null || value.isBlank()) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + userId, value, ttl);
            logger.debug("[TravelPreferenceCache] 已写入 Redis 偏好缓存, userId={}, ttl={}s", userId, ttl.getSeconds());
        } catch (Exception e) {
            logger.warn("[TravelPreferenceCache] 写入偏好缓存失败, userId={}: {}", userId, e.getMessage());
        }
    }

    /**
     * 失效指定用户的偏好缓存（偏好更新后调用，保证数据一致性）。
     */
    public void evict(String userId) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        try {
            redisTemplate.delete(KEY_PREFIX + userId);
            logger.debug("[TravelPreferenceCache] 已清除 Redis 偏好缓存, userId={}", userId);
        } catch (Exception e) {
            logger.warn("[TravelPreferenceCache] 清除偏好缓存失败, userId={}: {}", userId, e.getMessage());
        }
    }
}
