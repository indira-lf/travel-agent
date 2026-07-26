package com.travel.business.chat.service;

import com.travel.agent.common.PromptLoader;
import com.travel.business.chat.entity.ChatConversation;
import com.travel.business.chat.repository.ChatHistoryRepository;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 对话标题生成服务。
 * <p>基于 fastModel 直接调用，根据最新用户问题与意图识别结果异步生成会话标题，
 * 并在标题仍为默认值时更新 {@code chat_conversation.title}。</p>
 *
 * <p>该服务无工具调用、无多轮推理，直接通过 {@link Model#stream} 发起单次 LLM 请求，
 * 避免 ReActAgent 包装带来的额外开销。</p>
 *
 * @author Hollis
 */
@Service
public class ConversationTitleService {

    private static final Logger logger = LoggerFactory.getLogger(ConversationTitleService.class);

    private static final int MAX_TITLE_LENGTH = 24;
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Autowired
    @Qualifier("fastModel")
    private Model fastModel;
    @Autowired
    private ChatHistoryRepository chatHistoryRepository;
    @Autowired
    private ChatHistoryService chatHistoryService;

    /**
     * 根据用户问题与意图识别结果异步生成并更新会话标题。
     *
     * @param sessionId    会话 ID
     * @param userId       用户 ID
     * @param userQuestion 用户问题（改写后或原始）
     * @param intentJson   意图识别结果 JSON
     */
    @Async("titleTaskExecutor")
    public void updateTitleAsync(String sessionId, String userId, String userQuestion, String intentJson) {
        if (userQuestion == null || userQuestion.isBlank()) {
            return;
        }
        try {
            String title = generateTitle(userQuestion, intentJson);
            if (title == null || title.isBlank()) {
                return;
            }
            Optional<ChatConversation> optional = chatHistoryRepository.findConversationById(sessionId);
            if (optional.isEmpty()) {
                logger.debug("[TITLE] 会话尚未持久化，跳过标题更新 sessionId={}", sessionId);
                return;
            }
            ChatConversation conversation = optional.get();
            if (title.equals(conversation.getTitle())) {
                logger.debug("[TITLE] 标题无变化，跳过更新 sessionId={}", sessionId);
                return;
            }
            chatHistoryService.updateTitle(sessionId, userId, title);
            logger.info("[TITLE] 已更新会话标题 sessionId={}, title={}", sessionId, title);
        } catch (Exception e) {
            logger.warn("[TITLE] 生成或更新会话标题失败 sessionId={}: {}", sessionId, e.getMessage());
        }
    }

    private String generateTitle(String userQuestion, String intentJson) {
        String systemPrompt = PromptLoader.load("conversation-title-agent-system.md");
        String prompt = String.format(
                "用户问题：%s\n意图识别结果：%s\n请生成标题：",
                userQuestion.trim(),
                intentJson == null ? "" : intentJson.trim());

        List<Msg> messages = List.of(
                Msg.builder()
                        .role(MsgRole.SYSTEM)
                        .name("system")
                        .content(TextBlock.builder().text(systemPrompt).build())
                        .build(),
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("user")
                        .content(TextBlock.builder().text(prompt).build())
                        .build());

        List<ChatResponse> responses = fastModel.stream(messages, null, null).collectList().block(TIMEOUT);
        if (responses == null || responses.isEmpty()) {
            return null;
        }
        return cleanTitle(extractText(responses));
    }

    /**
     * 将流式 ChatResponse 中的所有 TextBlock 文本按顺序拼接为完整字符串。
     */
    private static String extractText(List<ChatResponse> responses) {
        StringBuilder sb = new StringBuilder();
        for (ChatResponse response : responses) {
            List<ContentBlock> blocks = response.getContent();
            if (blocks == null) {
                continue;
            }
            for (ContentBlock block : blocks) {
                if (block instanceof TextBlock textBlock) {
                    sb.append(textBlock.getText());
                }
            }
        }
        return sb.toString();
    }

    private String cleanTitle(String text) {
        if (text == null) {
            return null;
        }
        String title = text.trim()
                .replaceAll("^['\"`]+", "")
                .replaceAll("['\"`]+$", "")
                .trim();
        if (title.length() > MAX_TITLE_LENGTH) {
            title = title.substring(0, MAX_TITLE_LENGTH);
        }
        return title;
    }
}
