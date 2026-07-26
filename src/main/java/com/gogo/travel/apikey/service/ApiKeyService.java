package com.gogo.travel.apikey.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gogo.travel.apikey.entity.UserApiKeyEntry;
import com.gogo.travel.apikey.mapper.UserApiKeyMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;

/**
 * 用户 API Key 管理服务。
 *
 * <p>提供 API Key 的加密存储、解密读取，支持 Redis 缓存层加速。
 * 使用 AES-128 对称加密，密钥从配置文件读取。</p>
 *
 * @author Hollis
 */
@Service
public class ApiKeyService {

    private static final Logger logger = LoggerFactory.getLogger(ApiKeyService.class);

    private static final String REDIS_KEY_PREFIX = "apikey:";
    private static final Duration CACHE_TTL = Duration.ofDays(7);

    @Autowired
    private UserApiKeyMapper apiKeyMapper;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Value("${app.api-key-encrypt-secret:gogo-travel-default-secret}")
    private String encryptSecret;
    private SecretKeySpec aesKey;

    @PostConstruct
    public void init() {
        // 将配置密钥 SHA-256 哈希后取前 16 字节作为 AES-128 密钥
        this.aesKey = deriveAesKey(encryptSecret);
    }

    /**
     * 获取用户的 API Key（明文）。优先从 Redis 缓存读取，未命中则查数据库并回填缓存。
     *
     * @return 明文 API Key，若不存在返回 null
     */
    public String getApiKey(String userId, String provider) {
        if (userId == null || provider == null) {
            return null;
        }

        // 1. 尝试 Redis 缓存
        String cacheKey = REDIS_KEY_PREFIX + provider + ":" + userId;
        try {
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                logger.debug("[ApiKey] Redis 缓存命中, userId={}, provider={}", userId, provider);
                return decrypt(cached);
            }
        } catch (Exception e) {
            logger.warn("[ApiKey] Redis 读取失败, 降级到数据库", e);
        }

        // 2. 数据库查询
        UserApiKeyEntry entry = apiKeyMapper.selectOne(
                new LambdaQueryWrapper<UserApiKeyEntry>()
                        .eq(UserApiKeyEntry::getUserId, userId)
                        .eq(UserApiKeyEntry::getProvider, provider));

        if (entry == null) {
            logger.debug("[ApiKey] 数据库未找到, userId={}, provider={}", userId, provider);
            return null;
        }

        String encrypted = entry.getApiKeyEnc();

        // 3. 回填 Redis 缓存
        try {
            redisTemplate.opsForValue().set(cacheKey, encrypted, CACHE_TTL);
            logger.debug("[ApiKey] 已回填 Redis 缓存, userId={}, provider={}", userId, provider);
        } catch (Exception e) {
            logger.warn("[ApiKey] Redis 写入失败", e);
        }

        return decrypt(encrypted);
    }

    /**
     * 保存用户的 API Key（加密后存入数据库，同时更新 Redis 缓存）。
     */
    public void saveApiKey(String userId, String provider, String apiKey) {
        if (userId == null || provider == null || apiKey == null) {
            return;
        }

        String encrypted = encrypt(apiKey);

        // 1. 数据库 upsert：先删后插
        apiKeyMapper.delete(
                new LambdaQueryWrapper<UserApiKeyEntry>()
                        .eq(UserApiKeyEntry::getUserId, userId)
                        .eq(UserApiKeyEntry::getProvider, provider));
        apiKeyMapper.insert(new UserApiKeyEntry(userId, provider, encrypted));
        logger.info("[ApiKey] API Key 已加密保存到数据库, userId={}, provider={}", userId, provider);

        // 2. 更新 Redis 缓存
        String cacheKey = REDIS_KEY_PREFIX + provider + ":" + userId;
        try {
            redisTemplate.opsForValue().set(cacheKey, encrypted, CACHE_TTL);
        } catch (Exception e) {
            logger.warn("[ApiKey] Redis 缓存更新失败", e);
        }
    }

    /**
     * 检查用户是否已配置指定 provider 的 API Key。
     */
    public boolean hasApiKey(String userId, String provider) {
        return getApiKey(userId, provider) != null;
    }

    /**
     * 删除用户的 API Key。
     */
    public void deleteApiKey(String userId, String provider) {
        apiKeyMapper.delete(
                new LambdaQueryWrapper<UserApiKeyEntry>()
                        .eq(UserApiKeyEntry::getUserId, userId)
                        .eq(UserApiKeyEntry::getProvider, provider));

        String cacheKey = REDIS_KEY_PREFIX + provider + ":" + userId;
        try {
            redisTemplate.delete(cacheKey);
        } catch (Exception e) {
            logger.warn("[ApiKey] Redis 缓存删除失败", e);
        }

        logger.info("[ApiKey] API Key 已删除, userId={}, provider={}", userId, provider);
    }

    // ── AES 加密工具方法 ──────────────────────────────────────────────

    private String encrypt(String plainText) {
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, aesKey);
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception e) {
            throw new RuntimeException("AES 加密失败", e);
        }
    }

    private String decrypt(String base64Encrypted) {
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, aesKey);
            byte[] decrypted = cipher.doFinal(Base64.getDecoder().decode(base64Encrypted));
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("AES 解密失败", e);
        }
    }

    /**
     * 将配置密钥通过 SHA-256 哈希后取前 16 字节作为 AES-128 密钥。
     */
    private SecretKeySpec deriveAesKey(String secret) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(secret.getBytes(StandardCharsets.UTF_8));
            byte[] keyBytes = new byte[16];
            System.arraycopy(hash, 0, keyBytes, 0, 16);
            return new SecretKeySpec(keyBytes, "AES");
        } catch (Exception e) {
            throw new RuntimeException("AES 密钥派生失败", e);
        }
    }
}
