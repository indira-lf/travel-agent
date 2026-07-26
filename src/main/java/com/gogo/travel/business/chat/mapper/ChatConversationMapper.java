package com.gogo.travel.business.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gogo.travel.business.chat.entity.ChatConversation;
import org.apache.ibatis.annotations.Mapper;

/**
 * 对话会话 Mapper
 *
 * @author Hollis
 */
@Mapper
public interface ChatConversationMapper extends BaseMapper<ChatConversation> {
}
