package com.gogo.travel.agent.session;

import com.gogo.travel.agent.registry.AgentExecutionRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

/**
 * 基于 Redis Pub/Sub 的 Agent 优雅中断广播组件。
 *
 * <p>集群模式下，用户的 interrupt 请求可能落在任意节点上，而目标 Agent 实际运行在
 * 另一个节点。本组件让每个节点订阅统一的 {@code agent:interrupt} 频道；当某节点
 * 本地没有对应 session 的 Agent 时，将 sessionId 发布到该频道，持有该 Agent 的节点
 * 收到消息后执行 {@link AgentExecutionRegistry#interruptLocal(String)}，从而完成
 * 跨节点的优雅中断。</p>
 *
 * @author Hollis
 */
@Component
public class AgentInterruptBroadcast implements MessageListener {

    private static final Logger logger = LoggerFactory.getLogger(AgentInterruptBroadcast.class);

    public static final String INTERRUPT_CHANNEL = "agent:interrupt";

    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private AgentExecutionRegistry executionRegistry;
    @Autowired
    private RedisMessageListenerContainer listenerContainer;

    @PostConstruct
    public void init() {
        listenerContainer.addMessageListener(this, new ChannelTopic(INTERRUPT_CHANNEL));
        logger.info("[INTERRUPT_BROADCAST] 已订阅 Redis 频道: {}", INTERRUPT_CHANNEL);
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String sessionId = new String(message.getBody());
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        logger.info("[INTERRUPT_BROADCAST] 收到广播中断消息, sessionId={}", sessionId);
        executionRegistry.interruptLocal(sessionId);
    }

    /**
     * 向所有节点广播中断指定 session 的 Agent。
     */
    public void broadcastInterrupt(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        redisTemplate.convertAndSend(INTERRUPT_CHANNEL, sessionId);
        logger.info("[INTERRUPT_BROADCAST] 已广播中断消息, sessionId={}", sessionId);
    }
}
