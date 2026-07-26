package com.gogo.travel.business.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gogo.travel.business.chat.entity.ChatMessage;
import org.apache.ibatis.annotations.Mapper;

/**
 * 对话消息 Mapper
 *
 * @author Hollis
 */
@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {
}
