package com.travel.agent.session;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Agent 会话本地缓存管理器。
 *
 * <p>使用 Caffeine Cache 替代裸 {@code ConcurrentHashMap}，提供：
 * <ul>
 *   <li><b>容量上限</b>：最多缓存 {@value #MAX_SIZE} 个会话，超出后按 LRU 策略淘汰</li>
 *   <li><b>TTL 过期清理</b>：会话在最后一次访问后 {@value #EXPIRE_AFTER_ACCESS_MINUTES} 分钟自动过期</li>
 *   <li><b>被动清扫</b>：Caffeine 在读写操作时自动触发过期检查，无需额外定时任务</li>
 * </ul>
 *
 * <p>避免高并发或用户异常断开时 Agent 实例永驻内存。</p>
 *
 * @author Hollis
 */
@Component
public class AgentSessionManager {

    private static final Logger logger = LoggerFactory.getLogger(AgentSessionManager.class);

    /** 最大缓存会话数 */
    private static final int MAX_SIZE = 1024;

    /** 最后一次访问后过期时间（分钟） */
    private static final int EXPIRE_AFTER_ACCESS_MINUTES = 30;

    private final Cache<String, AgentSession> bySessionId;
    private final Cache<String, AgentSession> byProcessInstanceId;

    public AgentSessionManager() {
        // 先初始化 byProcessInstanceId，因为 bySessionId 的 removalListener 会引用它
        this.byProcessInstanceId = Caffeine.newBuilder()
                .maximumSize(MAX_SIZE)
                .expireAfterAccess(Duration.ofMinutes(EXPIRE_AFTER_ACCESS_MINUTES))
                .build();

        this.bySessionId = Caffeine.newBuilder()
                .maximumSize(MAX_SIZE)
                .expireAfterAccess(Duration.ofMinutes(EXPIRE_AFTER_ACCESS_MINUTES))
                .removalListener((String key, AgentSession value, RemovalCause cause) -> {
                    if (cause != RemovalCause.EXPLICIT && cause != RemovalCause.REPLACED) {
                        logger.info("[AgentSessionManager] 会话自动淘汰: sessionId={}, cause={}", key, cause);
                        // 联动清理 processInstanceId 索引
                        if (value != null && value.getProcessInstanceId() != null) {
                            byProcessInstanceId.invalidate(value.getProcessInstanceId());
                        }
                    }
                })
                .build();
    }

    public void register(AgentSession session) {
        bySessionId.put(session.getSessionId(), session);
        if (session.getProcessInstanceId() != null) {
            byProcessInstanceId.put(session.getProcessInstanceId(), session);
        }
    }

    public AgentSession getBySessionId(String sessionId) {
        return bySessionId.getIfPresent(sessionId);
    }

    public AgentSession getByApprovalId(String processInstanceId) {
        return byProcessInstanceId.getIfPresent(processInstanceId);
    }

    public void remove(String sessionId) {
        AgentSession session = bySessionId.getIfPresent(sessionId);
        bySessionId.invalidate(sessionId);
        if (session != null && session.getProcessInstanceId() != null) {
            byProcessInstanceId.invalidate(session.getProcessInstanceId());
        }
    }
}
