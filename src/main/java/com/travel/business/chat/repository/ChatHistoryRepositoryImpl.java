package com.travel.business.chat.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.travel.business.chat.entity.ChatConversation;
import com.travel.business.chat.entity.ChatMessage;
import com.travel.business.chat.mapper.ChatConversationMapper;
import com.travel.business.chat.mapper.ChatMessageMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/**
 * 对话历史数据访问实现（基于 MyBatis-Plus）。
 *
 * @author Hollis
 */
@Repository
public class ChatHistoryRepositoryImpl implements ChatHistoryRepository {

    @Autowired
    private ChatConversationMapper conversationMapper;
    @Autowired
    private ChatMessageMapper messageMapper;

    @Override
    public Optional<ChatConversation> findConversationById(String conversationId) {
        return Optional.ofNullable(conversationMapper.selectById(conversationId));
    }

    @Override
    public List<ChatConversation> findConversationsByUserId(String userId) {
        LambdaQueryWrapper<ChatConversation> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChatConversation::getUserId, userId)
                .orderByDesc(ChatConversation::getUpdatedAt);
        return conversationMapper.selectList(wrapper);
    }

    @Override
    public List<ChatMessage> findMessagesByConversationId(String conversationId) {
        LambdaQueryWrapper<ChatMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChatMessage::getConversationId, conversationId)
                .orderByAsc(ChatMessage::getCreatedAt);
        return messageMapper.selectList(wrapper);
    }

    @Override
    public Optional<ChatMessage> findMessageById(String messageId) {
        return Optional.ofNullable(messageMapper.selectById(messageId));
    }

    @Override
    public void saveConversation(ChatConversation conversation) {
        if (conversationMapper.selectById(conversation.getConversationId()) == null) {
            conversationMapper.insert(conversation);
        } else {
            conversationMapper.updateById(conversation);
        }
    }

    @Override
    public void saveMessage(ChatMessage message) {
        messageMapper.insert(message);
    }

    @Override
    public int updateFeedback(String messageId, String feedback, Instant feedbackAt) {
        LambdaUpdateWrapper<ChatMessage> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(ChatMessage::getMessageId, messageId)
                .set(ChatMessage::getFeedback, feedback)
                .set(ChatMessage::getFeedbackAt, feedbackAt);
        return messageMapper.update(wrapper);
    }

    @Override
    public void updateTitle(String conversationId, String title) {
        LambdaUpdateWrapper<ChatConversation> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(ChatConversation::getConversationId, conversationId)
                .set(ChatConversation::getTitle, title);
        conversationMapper.update(wrapper);
    }

    @Override
    public void deleteConversation(String conversationId) {
        conversationMapper.deleteById(conversationId);
    }

    @Override
    public void deleteMessagesByConversationId(String conversationId) {
        LambdaUpdateWrapper<ChatMessage> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(ChatMessage::getConversationId, conversationId)
                .set(ChatMessage::getDeleted, 1);
        messageMapper.update(wrapper);
    }
}
