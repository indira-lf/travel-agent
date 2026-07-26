package com.gogo.travel.agent.memory;

import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.memory.bailian.BailianLongTermMemory;
import io.agentscope.core.message.Msg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

/**
 * 面向旅行偏好的长期记忆实现。
 *
 * <p>基于 AgentScope {@link LongTermMemory} 接口，底层使用 {@link BailianLongTermMemory}
 * 完成跨会话的偏好持久化与召回。采用 {@code AGENT_CONTROL} 模式时，Agent 可通过
 * {@code record_to_memory} / {@code retrieve_from_memory} 工具自主决定何时保存或召回
 * 旅行偏好，例如：常飞航司、偏好酒店品牌、座位选择、出发时段等。</p>
 *
 * <p>为降低百炼慢调用开销，retrieve 结果通过 {@link TravelPreferenceMemoryCache}
 * 以用户维度缓存在 Redis 中；record 成功后主动失效缓存以保证数据一致性。</p>
 *
 * @author Hollis
 */
public class TravelPreferenceLongTermMemory implements LongTermMemory {

    private static final Logger logger = LoggerFactory.getLogger(TravelPreferenceLongTermMemory.class);

    private final BailianLongTermMemory baiLianMemory;
    private final TravelPreferenceMemoryCache cache;
    private final String userId;

    public TravelPreferenceLongTermMemory(BailianLongTermMemory baiLianMemory,
                                          TravelPreferenceMemoryCache cache,
                                          String userId) {
        this.baiLianMemory = baiLianMemory;
        this.cache = cache;
        this.userId = userId;
    }

    /**
     * 将消息中的旅行偏好信息记录到百炼长期记忆。
     */
    @Override
    public Mono<Void> record(List<Msg> msgs) {
        if (msgs == null || msgs.isEmpty()) {
            return Mono.empty();
        }
        logger.debug("[TravelPreferenceLTM] 准备记录用户 {} 的旅行偏好记忆，消息数={}", userId, msgs.size());
        return baiLianMemory.record(msgs)
                .doOnSuccess(v -> {
                    // 偏好已更新，主动失效缓存，避免读到旧画像
                    cache.evict(userId);
                    logger.debug("[TravelPreferenceLTM] 用户 {} 的旅行偏好记忆已保存，缓存已清除", userId);
                })
                .doOnError(e -> logger.warn("[TravelPreferenceLTM] 保存用户 {} 的旅行偏好记忆失败: {}",
                        userId, e.getMessage()));
    }

    /**
     * 从百炼长期记忆中召回与当前输入相关的旅行偏好。
     */
    @Override
    public Mono<String> retrieve(Msg msg) {
        if (msg == null) {
            return Mono.just("");
        }
        logger.debug("[TravelPreferenceLTM] 准备检索用户 {} 的旅行偏好记忆", userId);
        // 先查 Redis 缓存（阻塞调用放到 boundedElastic 线程），命中则不调百炼；
        // put(userId, "") 不会缓存空串，故空字符串可靠地表示「未命中」。
        return Mono.fromCallable(() -> {
                    String cached = cache.get(userId);
                    return cached != null ? cached : "";
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(cached -> {
                    if (!cached.isEmpty()) {
                        logger.debug("[TravelPreferenceLTM] 用户 {} 命中偏好缓存，跳过百炼检索", userId);
                        return Mono.just(cached);
                    }
                    return baiLianMemory.retrieve(msg)
                            .doOnNext(result -> {
                                cache.put(userId, result);
                                logger.debug("[TravelPreferenceLTM] 用户 {} 检索到旅行偏好记忆，长度={}",
                                        userId, result != null ? result.length() : 0);
                            })
                            .doOnError(e -> logger.warn("[TravelPreferenceLTM] 检索用户 {} 的旅行偏好记忆失败: {}",
                                    userId, e.getMessage()))
                            .onErrorReturn("");
                });
    }

    public String getUserId() {
        return userId;
    }
}
