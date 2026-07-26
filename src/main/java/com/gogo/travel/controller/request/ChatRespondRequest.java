package com.gogo.travel.controller.request;

/**
 * 用户回复 Agent 主动提问的请求体。
 *
 * @author Hollis
 */
public class ChatRespondRequest {

    private String sessionId;
    private String toolUseId;
    private Object response;

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getToolUseId() {
        return toolUseId;
    }

    public void setToolUseId(String toolUseId) {
        this.toolUseId = toolUseId;
    }

    public Object getResponse() {
        return response;
    }

    public void setResponse(Object response) {
        this.response = response;
    }
}
