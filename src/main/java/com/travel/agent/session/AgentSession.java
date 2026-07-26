package com.travel.agent.session;

import io.agentscope.core.ReActAgent;

/**
 * @author Hollis
 */
public class AgentSession {

    private final String sessionId;
    private final ReActAgent agent;
    private final String pendingToolUseId;
    private final String processInstanceId;

    public AgentSession(String sessionId, ReActAgent agent, String pendingToolUseId, String processInstanceId) {
        this.sessionId = sessionId;
        this.agent = agent;
        this.pendingToolUseId = pendingToolUseId;
        this.processInstanceId = processInstanceId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public ReActAgent getAgent() {
        return agent;
    }

    public String getPendingToolUseId() {
        return pendingToolUseId;
    }

    public String getProcessInstanceId() {
        return processInstanceId;
    }
}
