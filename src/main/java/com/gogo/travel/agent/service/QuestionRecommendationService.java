package com.gogo.travel.agent.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.gogo.travel.agent.common.ContinuationSignals;
import com.gogo.travel.agent.common.PromptLoader;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.List;

/**
 * 推荐问题生成服务。
 * <p>基于 fastModel 直接调用，根据用户问题与助手本轮最终回复生成下一步推荐问题列表。</p>
 *
 * <p>该服务无工具调用、无多轮推理，直接通过 {@link Model#stream} 发起单次 LLM 请求，
 * 避免 ReActAgent 包装带来的额外开销。</p>
 *
 * @author Hollis
 */
@Service
public class QuestionRecommendationService {

    private static final Logger logger = LoggerFactory.getLogger(QuestionRecommendationService.class);

    /** 推荐问题最大返回条数 */
    private static final int MAX_RECOMMENDED_QUESTIONS = 4;

    @Autowired
    @Qualifier("fastModel")
    private Model fastModel;

    /**
     * 根据用户问题和助手最终回复，生成下一步推荐问题列表。
     *
     * @param userQuestion     用户本轮输入（可为空）
     * @param assistantAnswer  助手本轮最终回复文本
     * @return 推荐问题列表，解析失败或无需推荐时返回空列表
     */
    public Mono<List<String>> recommend(String userQuestion, String assistantAnswer) {
        if (assistantAnswer == null || assistantAnswer.isBlank()) {
            return Mono.just(Collections.emptyList());
        }

        String prompt = buildInput(userQuestion, assistantAnswer);
        List<Msg> messages = List.of(
                Msg.builder()
                        .role(MsgRole.SYSTEM)
                        .name("system")
                        .content(TextBlock.builder()
                                .text(PromptLoader.load("question-recommendation-agent-system.md"))
                                .build())
                        .build(),
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("user")
                        .content(TextBlock.builder().text(prompt).build())
                        .build());

        return Mono.fromCallable(() -> fastModel.stream(messages, null, null).collectList().block())
                .map(this::parseSuggestions)
                .onErrorResume(e -> {
                    logger.warn("[QuestionRecommendation] 生成推荐问题失败: {}", e.getMessage());
                    return Mono.just(Collections.emptyList());
                });
    }

    private String buildInput(String userQuestion, String assistantAnswer) {
        StringBuilder sb = new StringBuilder();
        if (userQuestion != null && !userQuestion.isBlank()) {
            sb.append("用户问题：").append(userQuestion.trim()).append("\n\n");
        }
        sb.append("助手回复：").append(assistantAnswer.trim()).append("\n\n");

        // 注入可被 ChatController 识别的"继续信号词"分组，让推荐问题按需从中挑选快速操作
        sb.append("可被后端直接执行的快速操作关键词（点击后会跳过主智能体直接续跑当前子智能体）：\n");
        sb.append(ContinuationSignals.asGroupedPrompt());
        sb.append("\n请根据助手回复的当前阶段判断是否使用上述关键词：");
        sb.append("如果助手在抛出澄清/选择题，优先把助手列出的具体选项作为推荐项，不要硬塞'确定/确认'；");
        sb.append("如果助手在等待用户整体确认/修改/继续，再考虑从上述关键词中挑选。\n");

        sb.append("请根据以上对话生成接下来的推荐问题。");
        return sb.toString();
    }

    private List<String> parseSuggestions(List<ChatResponse> responses) {
        String text = extractText(responses);
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }
        try {
            String json = extractJsonBlock(text);
            if (json.isBlank()) {
                return Collections.emptyList();
            }
            JSONObject obj = JSON.parseObject(json);
            JSONArray array = obj.getJSONArray("questions");
            if (array == null) {
                return Collections.emptyList();
            }
            return array.stream()
                    .map(Object::toString)
                    .filter(s -> !s.isBlank())
                    .limit(MAX_RECOMMENDED_QUESTIONS)
                    .toList();
        } catch (Exception e) {
            logger.warn("[QuestionRecommendation] 解析推荐问题结果失败: {}, text={}", e.getMessage(), text);
            return Collections.emptyList();
        }
    }

    /**
     * 将流式 ChatResponse 中的所有 TextBlock 文本按顺序拼接为完整字符串。
     */
    private static String extractText(List<ChatResponse> responses) {
        if (responses == null || responses.isEmpty()) {
            return "";
        }
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

    /**
     * 从可能包含 markdown 代码块的文本中提取 JSON 对象字符串。
     */
    private String extractJsonBlock(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            int start = trimmed.indexOf('{');
            int end = trimmed.lastIndexOf('}');
            if (start >= 0 && end > start) {
                return trimmed.substring(start, end + 1);
            }
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed;
    }
}
