package com.travel.agent.hook;

import com.travel.agent.session.ActiveAgentSessionStore;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * @author Hollis
 */
public class ActiveAgentPersistenceHook implements Hook {

    private static final Logger logger = LoggerFactory.getLogger(ActiveAgentPersistenceHook.class);

    private final ActiveAgentSessionStore activeAgentSessionStore;

    public ActiveAgentPersistenceHook(ActiveAgentSessionStore activeAgentSessionStore) {
        this.activeAgentSessionStore = activeAgentSessionStore;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (!(event instanceof PreReasoningEvent)) {
            return Mono.just(event);
        }

        return Mono.deferContextual(ctx -> {

            String sessionId = ctx.getOrDefault("sessionId", null);

            if (sessionId == null) {
                logger.debug("[ActiveAgentPersistence]  未从 ReactorContext 中提取到 sessionId，跳过对话记忆持久化");
                return Mono.just(event);
            }
            String agentName = event.getAgent() != null ? event.getAgent().getName() : null;

            activeAgentSessionStore.setActiveAgent(sessionId, agentName);
            logger.info("[ActiveAgentPersistence] session={} 活跃 Agent 已切换为 {}", sessionId, agentName);
            return Mono.just(event);
        });
    }
}
