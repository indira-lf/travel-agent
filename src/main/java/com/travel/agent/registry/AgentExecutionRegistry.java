package com.travel.agent.registry;

import com.travel.agent.hook.AgentExecutionRegistryHook;
import com.travel.agent.hook.SessionPersistenceHook;
import com.travel.agent.service.AgentPipelineService;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.session.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地 Agent 执行注册表。
 *
 * <p>集群模式下每个节点独立维护一份 {@code Map<sessionId, Set<Agent>>}，
 * 记录当前节点上正在运行的 Agent 实例。由于 MasterAgent 会通过 SubAgentTool 调用子智能体，
 * 一个 session 在同一时刻可能存在多个嵌套运行的 Agent，因此使用 Set 保存所有活跃实例。
 * 当用户触发中断时，优先查询本地注册表并调用每个实例的 {@link Agent#interrupt()}
 * 实现优雅中断；本地未命中时再通过 Redis Pub/Sub 广播到其他节点。</p>
 *
 * <p>注册/注销由 {@link com.travel.controller.ChatController}、
 * {@link AgentPipelineService} 以及
 * {@link AgentExecutionRegistryHook} 在 Agent 执行前后自动维护。</p>
 *
 * <p>本类使用 {@link Agent} 接口类型保存实例（兼容 {@link ReActAgent} 与
 * {@code IntentRecognitionAgent} 等自定义 Agent），中断时的 memory 持久化会按实际类型分派。</p>
 *
 * @author Hollis
 */
@Component
public class AgentExecutionRegistry {

    @Autowired
    private Session agentSession;

    private static final Logger logger = LoggerFactory.getLogger(AgentExecutionRegistry.class);

    private final Map<String, Set<Agent>> localAgents = new ConcurrentHashMap<>();

    /**
     * 将指定 session 当前正在执行的 Agent 注册到本地表。
     */
    public void register(String sessionId, Agent agent) {
        if (sessionId == null || agent == null) {
            return;
        }
        Set<Agent> agents = localAgents.computeIfAbsent(sessionId, k -> ConcurrentHashMap.newKeySet());
        boolean added = agents.add(agent);
        if (added) {
            logger.debug("[EXEC_REGISTRY] session={} Agent={} 已注册到本地执行表", sessionId, agent.getName());
        }
    }

    /**
     * 查询本地注册表中指定 session 的任意一个 Agent。
     */
    public Agent getLocalAgent(String sessionId) {
        Set<Agent> agents = localAgents.get(sessionId);
        if (agents == null || agents.isEmpty()) {
            return null;
        }
        return agents.iterator().next();
    }

    /**
     * 本地优雅中断指定 session 的所有 Agent。
     *
     * @return true 表示本地命中并执行了 interrupt；false 表示本地没有该 session 的执行实例。
     */
    public boolean interruptLocal(String sessionId) {
        Set<Agent> agents = localAgents.remove(sessionId);
        if (agents == null || agents.isEmpty()) {
            logger.debug("[EXEC_REGISTRY] session={} 本地无运行中 Agent", sessionId);
            return false;
        }
        boolean interrupted = false;
        for (Agent agent : agents) {
            try {
                agent.interrupt();
                logger.info("[EXEC_REGISTRY] 优雅中断 session={} 的 Agent: {}", sessionId, agent.getName());
                interrupted = true;
            } catch (Exception e) {
                logger.error("[EXEC_REGISTRY] 本地中断 Agent 失败 session={} agent={}", sessionId, agent.getName(), e);
            }
            // AgentScope 优雅中断路径会跳过 PostCallEvent，SessionPersistenceHook 无法自动保存。
            // 在中断发生时立即把当前 memory 写入 Session，避免子智能体被打断后记忆丢失。
            persistInterruptedMemory(agent, sessionId);
        }
        return interrupted;
    }

    /**
     * 持久化被中断 Agent 的当前对话记忆。
     *
     * <p>AgentScope 的优雅中断路径通过 {@code onErrorResume} 直接返回恢复消息，
     * 不会触发 {@code PostCallEvent}，因此 {@link SessionPersistenceHook}
     * 无法自动保存。这里在调用 {@link Agent#interrupt()} 后手动把 memory_messages 写入
     * Session，保证被中断轮次的用户输入不会丢失。</p>
     *
     * <p>不同 Agent 实现 memory 入口不同：
     * <ul>
     *   <li>{@link ReActAgent}：自身持有 {@code Memory}，直接调用 {@code getMemory()}；</li>
     *   <li>{@code IntentRecognitionAgent}：无状态的一次性识别器，不持有 memory，跳过持久化；</li>
     *   <li>其它自定义 Agent：若未提供 memory 访问入口则跳过持久化。</li>
     * </ul>
     */
    private void persistInterruptedMemory(Agent agent, String sessionId) {
        try {
            String agentName = agent.getName();
            String sessionKey = sessionId + ":" + agentName;
            Memory memory = resolveMemory(agent);
            if (memory == null) {
                logger.debug("[SessionPersistence] Agent {} 不暴露 memory 入口，跳过被中断时的记忆保存", agentName);
                return;
            }
            memory.saveTo(agentSession, sessionKey);
            logger.debug("[SessionPersistence] 已保存被中断 Agent 的对话记忆: sessionId={}, agent={}", sessionId, agentName);
        } catch (Exception e) {
            logger.warn("[SessionPersistence] 保存被中断 Agent 的对话记忆失败 sessionId={} agent={}: {}",
                    sessionId, agent.getName(), e.getMessage());
        }
    }

    /**
     * 从不同 Agent 实现中解析 {@link Memory}。
     */
    private Memory resolveMemory(Agent agent) {
        if (agent instanceof ReActAgent reAct) {
            return reAct.getMemory();
        }
        return null;
    }

    /**
     * 移除指定 session 下的某个 Agent 实例。
     */
    public void remove(String sessionId, Agent agent) {
        if (sessionId == null || agent == null) {
            return;
        }
        Set<Agent> agents = localAgents.get(sessionId);
        if (agents == null) {
            return;
        }
        if (agents.remove(agent)) {
            logger.debug("[EXEC_REGISTRY] session={} Agent={} 已从本地执行表移除", sessionId, agent.getName());
        }
        if (agents.isEmpty()) {
            localAgents.remove(sessionId, Collections.emptySet());
        }
    }

    /**
     * 执行流自然结束时移除该 session 下的所有注册。
     */
    public void remove(String sessionId) {
        localAgents.remove(sessionId);
        logger.debug("[EXEC_REGISTRY] session={} 已从本地执行表移除", sessionId);
    }
}
