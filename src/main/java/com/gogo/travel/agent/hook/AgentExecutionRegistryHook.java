package com.gogo.travel.agent.hook;

import com.gogo.travel.agent.context.AgentSessionContext;
import com.gogo.travel.agent.context.AgentSessionContextHolder;
import com.gogo.travel.agent.registry.AgentExecutionRegistry;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.hook.PreCallEvent;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 全局 Agent 执行注册 Hook。
 *
 * <p>通过 {@link AgentBase#addSystemHook(io.agentscope.core.hook.Hook)} 注册为系统级 Hook，
 * 自动监听所有 Agent（包括 MasterAgent 通过 SubAgentTool 调用的子智能体）的
 * {@link PreCallEvent} 与 {@link PostCallEvent}，将其纳入 {@link AgentExecutionRegistry}。
 * 这样当用户触发“停止生成”时，可以一并中断当前 session 下所有嵌套运行的 Agent。</p>
 *
 * <p>该 Hook 与 {@code ChatController} / {@code AgentPipelineService} 中显式注册互为补充，
 * 注册表内部使用 Set 去重，同一 Agent 多次注册不会产生副作用。</p>
 *
 * @author Hollis
 */
@Component
public class AgentExecutionRegistryHook implements Hook {

    private static final Logger logger = LoggerFactory.getLogger(AgentExecutionRegistryHook.class);

    @Autowired
    private AgentExecutionRegistry executionRegistry;

    @PostConstruct
    public void init() {
        AgentBase.addSystemHook(this);
        logger.info("[EXEC_REGISTRY_HOOK] 已注册为 AgentScope 系统 Hook");
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (!(event instanceof PreCallEvent) && !(event instanceof PostCallEvent)) {
            return Mono.just(event);
        }

        return Mono.deferContextual(ctx -> {
            String sessionId = ctx.getOrDefault("sessionId", null);
            if (sessionId == null) {
                // SubAgentTool 执行时可能未透传 Reactor Context，回退到 TTL 中的会话上下文
                AgentSessionContext sessionCtx = AgentSessionContextHolder.get();
                if (sessionCtx != null) {
                    sessionId = sessionCtx.getSessionId();
                }
            }
            if (sessionId == null) {
                logger.debug("[EXEC_REGISTRY_HOOK] 无法获取 sessionId，跳过 event={}",
                        event.getClass().getSimpleName());
                return Mono.just(event);
            }

            if (!(event.getAgent() instanceof Agent agent)) {
                return Mono.just(event);
            }

            if (event instanceof PreCallEvent) {
                executionRegistry.register(sessionId, agent);
            } else {
                executionRegistry.remove(sessionId, agent);
            }

            return Mono.just(event);
        });
    }
}
