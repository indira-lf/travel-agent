package com.travel.agent.registry;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 维护 sessionId → SseEmitter 的映射，供 ProgressNotifierHook 在 Agent 执行过程中
 * 实时向前端推送进度事件。
 *
 * @author Hollis
 */
@Component
public class SseEmitterRegistry {

    private static final Logger logger = LoggerFactory.getLogger(SseEmitterRegistry.class);

    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    /**
     * 注册一个 SseEmitter，并绑定完成/超时/异常回调自动清除。
     */
    public void register(String sessionId, SseEmitter emitter) {
        emitters.put(sessionId, emitter);
        emitter.onCompletion(() -> {
            emitters.remove(sessionId);
            logger.debug("[SSE_REGISTRY] session={} emitter 已完成并移除", sessionId);
        });
        emitter.onTimeout(() -> {
            emitters.remove(sessionId);
            logger.warn("[SSE_REGISTRY] session={} emitter 超时并移除（Agent 可能仍在运行，请检查超时配置）", sessionId);
        });
        emitter.onError(e -> {
            emitters.remove(sessionId);
            logger.debug("[SSE_REGISTRY] session={} emitter 异常并移除: {}", sessionId, e.getMessage());
        });
        logger.debug("[SSE_REGISTRY] session={} emitter 已注册", sessionId);
    }

    /**
     * 获取当前 session 对应的 SseEmitter，若不存在返回 null。
     */
    public SseEmitter get(String sessionId) {
        return emitters.get(sessionId);
    }

    /**
     * 手动移除（一般由回调自动处理，备用）。
     */
    public void remove(String sessionId) {
        emitters.remove(sessionId);
    }
}
