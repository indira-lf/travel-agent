package com.travel.business.chat.repository;

import com.travel.business.chat.entity.ChatConversation;
import com.travel.business.chat.entity.ChatMessage;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 对话历史数据访问接口。
 *
 * @author Hollis
 */
public interface ChatHistoryRepository {

    Optional<ChatConversation> findConversationById(String conversationId);

    List<ChatConversation> findConversationsByUserId(String userId);

    List<ChatMessage> findMessagesByConversationId(String conversationId);

    Optional<ChatMessage> findMessageById(String messageId);

    void saveConversation(ChatConversation conversation);

    void saveMessage(ChatMessage message);

    void updateTitle(String conversationId, String title);

    /**
     * 更新指定消息的用户反馈。
     *
     * @param messageId 消息 ID
     * @param feedback  反馈值（LIKE / DISLIKE / null 表示清空）
     * @param feedbackAt 反馈时间
     * @return 受影响行数（用于上层判断消息是否存在）
     */
    int updateFeedback(String messageId, String feedback, Instant feedbackAt);

    void deleteConversation(String conversationId);

    void deleteMessagesByConversationId(String conversationId);
}
