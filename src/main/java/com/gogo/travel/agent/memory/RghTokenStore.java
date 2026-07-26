package com.gogo.travel.agent.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * rgh CLI 用户 Token 隔离存储。
 *
 * <p>将每个用户的 OAuth Token 存储在 Redis 中，Key 为 {@code rgh:token:{userId}}，
 * 解决多用户共享同一 rgh CLI 实例时 Token 互相覆盖的问题。</p>
 *
 *
 * @author Hollis
 */
@Component
public class RghTokenStore {

    private static final Logger logger = LoggerFactory.getLogger(RghTokenStore.class);

    private static final String KEY_PREFIX = "rgh:token:";
    /** Token 有效期 30 天，与 rgh OAuth Token 默认有效期对齐 */
    private static final Duration TOKEN_TTL = Duration.ofDays(30);

    @Autowired
    private StringRedisTemplate redisTemplate;

    /**
     * 获取指定用户的 rgh Token（JSON 字符串）。
     *
     * @return Token JSON，若不存在返回 null
     */
    public String getToken(String userId) {
        if (userId == null) {
            return null;
        }
        try {
            String token = redisTemplate.opsForValue().get(KEY_PREFIX + userId);
            if (token != null) {
                logger.debug("[RghTokenStore] 命中 Redis Token, userId={}", userId);
            }
            return token;
        } catch (Exception e) {
            logger.warn("[RghTokenStore] 从 Redis 读取 Token 失败, userId={}", userId, e);
            return null;
        }
    }

    /**
     * 保存指定用户的 rgh Token 到 Redis。
     */
    public void saveToken(String userId, String tokenJson) {
        if (userId == null || tokenJson == null) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + userId, tokenJson, TOKEN_TTL);
            logger.info("[RghTokenStore] Token 已保存到 Redis, userId={}", userId);
        } catch (Exception e) {
            logger.warn("[RghTokenStore] 保存 Token 到 Redis 失败, userId={}", userId, e);
        }
    }

    /**
     * 检查指定用户是否已保存 Token。
     */
    public boolean hasToken(String userId) {
        if (userId == null) {
            return false;
        }
        try {
            Boolean exists = redisTemplate.hasKey(KEY_PREFIX + userId);
            return Boolean.TRUE.equals(exists);
        } catch (Exception e) {
            logger.warn("[RghTokenStore] 检查 Token 是否存在失败, userId={}", userId, e);
            return false;
        }
    }

    /**
     * 删除指定用户的 Token（用于退出登录）。
     */
    public void deleteToken(String userId) {
        if (userId == null) {
            return;
        }
        try {
            redisTemplate.delete(KEY_PREFIX + userId);
            logger.info("[RghTokenStore] Token 已从 Redis 删除, userId={}", userId);
        } catch (Exception e) {
            logger.warn("[RghTokenStore] 删除 Token 失败, userId={}", userId, e);
        }
    }
}
