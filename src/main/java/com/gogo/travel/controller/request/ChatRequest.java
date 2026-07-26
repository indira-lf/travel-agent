package com.gogo.travel.controller.request;

/**
 * Chat 请求体。userId 已由 Sa-Token 从会话读取，无需前端传入。
 *
 * @author Hollis
 */
public class ChatRequest {

    private String message;

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
