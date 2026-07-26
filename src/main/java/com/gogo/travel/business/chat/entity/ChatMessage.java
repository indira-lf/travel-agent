package com.gogo.travel.business.chat.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.gogo.travel.config.InstantTypeHandler;
import java.time.Instant;

/**
 * 对话消息实体，对应 chat_message 表。
 *
 * @author Hollis
 */
@TableName(value = "chat_message", autoResultMap = true)
public class ChatMessage {

    @TableId(value = "message_id", type = IdType.INPUT)
    private String messageId;

    private String conversationId;

    private String role;

    private String content;

    private String agentName;

    /**
     * 扩展信息 JSON 字符串，可存放进度快照、推荐问题等。
     */
    private String extra;

    /**
     * 用户反馈：LIKE 点赞 / DISLIKE 点踩 / NULL 未反馈
     */
    private String feedback;

    /**
     * 反馈时间（毫秒为单位的 Instant）。
     */
    @TableField(typeHandler = InstantTypeHandler.class)
    private Instant feedbackAt;

    @TableField(fill = FieldFill.INSERT, typeHandler = InstantTypeHandler.class)
    private Instant createdAt;

    @TableLogic
    @TableField(value = "deleted")
    private Integer deleted;

    public ChatMessage() {
    }

    public ChatMessage(String messageId, String conversationId, String role, String content,
                       String agentName, String extra) {
        this.messageId = messageId;
        this.conversationId = conversationId;
        this.role = role;
        this.content = content;
        this.agentName = agentName;
        this.extra = extra;
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getAgentName() {
        return agentName;
    }

    public void setAgentName(String agentName) {
        this.agentName = agentName;
    }

    public String getExtra() {
        return extra;
    }

    public void setExtra(String extra) {
        this.extra = extra;
    }

    public String getFeedback() {
        return feedback;
    }

    public void setFeedback(String feedback) {
        this.feedback = feedback;
    }

    public Instant getFeedbackAt() {
        return feedbackAt;
    }

    public void setFeedbackAt(Instant feedbackAt) {
        this.feedbackAt = feedbackAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Integer getDeleted() {
        return deleted;
    }

    public void setDeleted(Integer deleted) {
        this.deleted = deleted;
    }
}
