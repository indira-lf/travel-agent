package com.travel.agent.session;

import com.travel.agent.session.record.PendingToolState;
import io.agentscope.core.session.Session;
import io.agentscope.core.state.SimpleSessionKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 集群化 ask_user 暂停状态存储。
 *
 * <p>当 Agent 触发 {@link io.agentscope.core.tool.ToolSuspendException} 并进入
 * TOOL_SUSPENDED 状态时，将发起提问的 agentName、toolUseId 等关键元数据持久化到 ASJ
 * {@link Session}（MySQL / Redis），使集群任意节点在收到用户回复时均可重建 Agent 实例
 * 并完成续跑，而无需依赖
 * {@link AgentSessionManager} 本地内存。</p>
 *
 * <p><b>Session Key 规则：</b>{@code sessionId + ":pending_tool"}，与
 * {@link ActiveAgentSessionStore} 使用的 {@code ":router"} 后缀隔离。</p>
 *
 * @author Hollis
 */
@Component
public class PendingToolSessionStore {

    private static final Logger logger = LoggerFactory.getLogger(PendingToolSessionStore.class);

    private static final String PENDING_TOOL_FIELD = "pendingTool";
    private static final String PENDING_TOOL_SESSION_KEY_SUFFIX = ":pending_tool";

    @Autowired
    private Session session;

    /**
     * 将 ask_user 暂停状态持久化到共享 Session。
     *
     * @param sessionId  对话会话 ID
     * @param agentName  发起提问的 Agent 名称
     * @param toolUseId  ToolUseBlock 的 id
     * @param toolName   工具名称（ask_user）
     */
    public void save(String sessionId, String agentName, String toolUseId, String toolName) {
        try {
            session.save(buildSessionKey(sessionId), PENDING_TOOL_FIELD,
                    new PendingToolState(agentName, toolUseId, toolName));
            logger.debug("[PendingToolStore] 已持久化 pendingTool, sessionId={}, agent={}, toolUseId={}",
                    sessionId, agentName, toolUseId);
        } catch (Exception e) {
            logger.error("[PendingToolStore] 持久化 pendingTool 失败, sessionId={}", sessionId, e);
        }
    }

    /**
     * 从共享 Session 读取 ask_user 暂停状态。
     *
     * @param sessionId 对话会话 ID
     * @return {@link PendingToolState}，若不存在则返回 null
     */
    public PendingToolState get(String sessionId) {
        try {
            return session.get(buildSessionKey(sessionId), PENDING_TOOL_FIELD, PendingToolState.class)
                    .orElse(null);
        } catch (Exception e) {
            logger.error("[PendingToolStore] 读取 pendingTool 失败, sessionId={}", sessionId, e);
            return null;
        }
    }

    /**
     * 清除共享 Session 中的 ask_user 暂停状态。
     *
     * <p>在续跑成功完成或中断后调用，保证下一轮不会误读到残留状态。</p>
     *
     * @param sessionId 对话会话 ID
     */
    public void clear(String sessionId) {
        try {
            session.delete(buildSessionKey(sessionId));
            logger.debug("[PendingToolStore] 已清除 pendingTool, sessionId={}", sessionId);
        } catch (Exception e) {
            logger.error("[PendingToolStore] 清除 pendingTool 失败, sessionId={}", sessionId, e);
        }
    }

    private SimpleSessionKey buildSessionKey(String sessionId) {
        return SimpleSessionKey.of(sessionId + PENDING_TOOL_SESSION_KEY_SUFFIX);
    }
}
