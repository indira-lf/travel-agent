package com.gogo.travel.agent.session.record;

import io.agentscope.core.state.State;

/**
 * 会话中被 ToolSuspendException 暂停的 ask_user 工具的持久化状态。
 *
 * <p>当 Agent 调用 ask_user 后进入 TOOL_SUSPENDED 状态，框架会把 {@link io.agentscope.core.message.ToolUseBlock}
 * 写入 memory_messages。但 {@link com.gogo.travel.agent.session.AgentSessionManager} 仅保存在本节点内存，
 * 集群下其他节点无法访问。通过把当前挂起的 agentName / toolUseId / toolName 持久化到 ASJ Session，
 * 任意节点均可在收到用户回复时重建 Agent 实例并正确续跑。</p>
 *
 * @param agentName  发起 ask_user 的 Agent 名称，用于重建同类 prototype 实例
 * @param toolUseId  对应 ToolUseBlock 的 id，回填 ToolResultBlock 时必须匹配
 * @param toolName   工具名称（固定为 ask_user），留作扩展预留
 *
 * @author Hollis
 */
public record PendingToolState(
        String agentName,
        String toolUseId,
        String toolName
) implements State {
}
