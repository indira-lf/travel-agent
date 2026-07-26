package com.gogo.travel.business.chat.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import com.gogo.travel.business.chat.entity.ChatConversation;
import com.gogo.travel.business.chat.entity.ChatMessage;
import com.gogo.travel.business.chat.repository.ChatHistoryRepository;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 对话历史服务：负责会话与消息的持久化、查询与删除。
 *
 * @author Hollis
 */
@Service
public class ChatHistoryService {

    private static final Logger logger = LoggerFactory.getLogger(ChatHistoryService.class);

    private static final String DEFAULT_TITLE = "新对话";

    /** 对话标题最大字符数 */
    private static final int TITLE_MAX_LENGTH = 24;

    @Autowired
    private ChatHistoryRepository chatHistoryRepository;

    /**
     * 查询某用户的所有会话，按最后更新时间倒序。
     */
    public List<ConversationView> listConversations(String userId) {
        return chatHistoryRepository.findConversationsByUserId(userId).stream()
                .map(this::toConversationView)
                .collect(Collectors.toList());
    }

    /**
     * 查询某会话下的所有消息（需校验会话属于当前用户）。
     */
    public List<MessageView> listMessages(String conversationId, String userId) {
        ChatConversation conversation = chatHistoryRepository.findConversationById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在"));
        if (!Objects.equals(conversation.getUserId(), userId)) {
            throw new IllegalArgumentException("无权访问该会话");
        }
        return chatHistoryRepository.findMessagesByConversationId(conversationId).stream()
                .map(this::toMessageView)
                .collect(Collectors.toList());
    }

    /**
     * 保存一条用户消息，同时创建/更新会话标题。
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveUserMessage(String conversationId, String userId, String content) {
        ensureConversationExists(conversationId, userId);
        if (content != null && !content.isBlank()) {
            updateTitleFromFirstUserMessage(conversationId, content);
        }
        ChatMessage message = new ChatMessage(
                generateMessageId(), conversationId, "user", content, null, null);
        chatHistoryRepository.saveMessage(message);
        logger.debug("[CHAT_HISTORY] 保存用户消息 conversationId={}", conversationId);
    }

    /**
     * 保存一条 AI 回复消息。
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveAssistantMessage(String conversationId, String userId, String content,
                                     String agentName, String extraJson) {
        ensureConversationExists(conversationId, userId);
        ChatMessage message = new ChatMessage(
                generateMessageId(), conversationId, "agent", content, agentName, extraJson);
        chatHistoryRepository.saveMessage(message);
        logger.debug("[CHAT_HISTORY] 保存AI消息 conversationId={}, agent={}", conversationId, agentName);
    }

    /**
     * 保存一条系统消息（如错误提示、中断提示）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveSystemMessage(String conversationId, String userId, String content) {
        ensureConversationExists(conversationId, userId);
        ChatMessage message = new ChatMessage(
                generateMessageId(), conversationId, "system", content, null, null);
        chatHistoryRepository.saveMessage(message);
    }

    /**
     * 反馈状态：点赞 / 点踩。
     */
    public enum Feedback {
        /**
         * 点赞
         */
        LIKE,
        /**
         * 点踩
         */
        DISLIKE
    }

    /**
     * 更新某条消息的用户反馈。feedback 为 null 时表示清空反馈。
     *
     * @throws IllegalArgumentException 消息不存在或无权访问
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateFeedback(String messageId, String userId, String feedback) {
        ChatMessage message = chatHistoryRepository.findMessageById(messageId)
                .orElseThrow(() -> new IllegalArgumentException("消息不存在"));
        ChatConversation conversation = chatHistoryRepository
                .findConversationById(message.getConversationId())
                .orElseThrow(() -> new IllegalArgumentException("会话不存在"));
        if (!Objects.equals(conversation.getUserId(), userId)) {
            throw new IllegalArgumentException("无权访问该消息");
        }
        String normalized = normalizeFeedback(feedback);
        Instant feedbackAt = normalized == null ? null : Instant.now();
        int rows = chatHistoryRepository.updateFeedback(messageId, normalized, feedbackAt);
        if (rows == 0) {
            throw new IllegalArgumentException("消息不存在");
        }
        logger.info("[CHAT_HISTORY] 更新反馈 messageId={}, feedback={}", messageId, normalized);
    }

    private String normalizeFeedback(String feedback) {
        if (feedback == null) {
            return null;
        }
        String trimmed = feedback.trim();
        if (trimmed.isEmpty() || "null".equalsIgnoreCase(trimmed) || "CLEAR".equalsIgnoreCase(trimmed)) {
            return null;
        }
        return trimmed.toUpperCase();
    }

    /**
     * 更新会话标题。
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateTitle(String conversationId, String userId, String title) {
        ChatConversation conversation = chatHistoryRepository.findConversationById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在"));
        if (!Objects.equals(conversation.getUserId(), userId)) {
            throw new IllegalArgumentException("无权访问该会话");
        }
        chatHistoryRepository.updateTitle(conversationId, title);
    }

    /**
     * 删除会话及其消息（逻辑删除）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteConversation(String conversationId, String userId) {
        ChatConversation conversation = chatHistoryRepository.findConversationById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在"));
        if (!Objects.equals(conversation.getUserId(), userId)) {
            throw new IllegalArgumentException("无权访问该会话");
        }
        chatHistoryRepository.deleteMessagesByConversationId(conversationId);
        chatHistoryRepository.deleteConversation(conversationId);
        logger.info("[CHAT_HISTORY] 删除会话 conversationId={}, userId={}", conversationId, userId);
    }

    private void ensureConversationExists(String conversationId, String userId) {
        if (chatHistoryRepository.findConversationById(conversationId).isEmpty()) {
            ChatConversation conversation = new ChatConversation(conversationId, userId, DEFAULT_TITLE);
            chatHistoryRepository.saveConversation(conversation);
        }
    }

    private void updateTitleFromFirstUserMessage(String conversationId, String content) {
        ChatConversation conversation = chatHistoryRepository.findConversationById(conversationId)
                .orElse(null);
        if (conversation == null) {
            return;
        }
        if (DEFAULT_TITLE.equals(conversation.getTitle())) {
            String title = content.length() > TITLE_MAX_LENGTH ? content.substring(0, TITLE_MAX_LENGTH) : content;
            chatHistoryRepository.updateTitle(conversationId, title);
        }
    }

    private String generateMessageId() {
        return "msg_" + UUID.randomUUID().toString().replace("-", "");
    }

    private ConversationView toConversationView(ChatConversation conversation) {
        return new ConversationView(
                conversation.getConversationId(),
                conversation.getTitle(),
                toEpochMilli(conversation.getCreatedAt()),
                toEpochMilli(conversation.getUpdatedAt()));
    }

    private MessageView toMessageView(ChatMessage message) {
        Map<String, Object> extraMap = null;
        if (message.getExtra() != null && !message.getExtra().isBlank()) {
            try {
                extraMap = JSON.parseObject(message.getExtra(), new TypeReference<Map<String, Object>>() {
                });
            } catch (Exception e) {
                logger.warn("[CHAT_HISTORY] 解析消息 extra 失败 messageId={}: {}",
                        message.getMessageId(), e.getMessage());
            }
        }
        return new MessageView(
                message.getMessageId(),
                message.getRole(),
                message.getContent(),
                message.getAgentName(),
                toEpochMilli(message.getCreatedAt()),
                extraMap == null ? Collections.emptyMap() : extraMap,
                message.getFeedback(),
                toEpochMilli(message.getFeedbackAt()));
    }

    private long toEpochMilli(Instant instant) {
        return instant == null ? System.currentTimeMillis() : instant.toEpochMilli();
    }

    public record ConversationView(String id, String title, long createdAt, long updatedAt) {
    }

    public record MessageView(String id, String role, String content, String agentName,
                              long timestamp, Map<String, Object> extra,
                              String feedback, long feedbackAt) {
    }
}
