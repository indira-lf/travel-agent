package com.gogo.travel.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.gogo.travel.agent.common.ContinuationSignals;
import com.gogo.travel.agent.context.AgentSessionContext;
import com.gogo.travel.agent.context.AgentSessionContextHolder;
import com.gogo.travel.agent.registry.AgentRegistry;
import com.gogo.travel.agent.service.ChatAgentExecutor;
import com.gogo.travel.agent.session.ActiveAgentSessionStore;
import com.gogo.travel.business.chat.service.ChatHistoryService;
import com.gogo.travel.controller.request.ChatRequest;
import com.gogo.travel.controller.request.ChatRespondRequest;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 聊天接口（瘦 Controller）。
 *
 * <p>仅负责 HTTP 协议解析、参数校验与上下文设置，实际执行编排委托给
 * {@link ChatAgentExecutor}。</p>
 *
 * @author Hollis
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger logger = LoggerFactory.getLogger(ChatController.class);

    /**
     * 明显切换意图或开启新话题的信号词。命中这些词时强制回到主智能体重新协调。
     */
    private static final Set<String> TOPIC_SWITCH_SIGNALS = Set.of(
            "另外", "换个", "换一个", "不想", "不要这个", "重新申请", "重新提交", "另外一件事");

    @Autowired
    private AgentRegistry agentRegistry;
    @Autowired
    private ActiveAgentSessionStore activeAgentSessionStore;
    @Autowired
    private ChatAgentExecutor agentExecutor;
    @Autowired
    private ChatHistoryService chatHistoryService;

    // ===================== 核心聊天接口 =====================

    @PostMapping("/{sessionId}")
    @SaCheckLogin
    public SseEmitter chat(@PathVariable String sessionId, @RequestBody ChatRequest request) {
        String userId = StpUtil.getLoginIdAsString();
        AgentSessionContextHolder.set(new AgentSessionContext(userId, sessionId));

        String message = request.getMessage() != null ? request.getMessage() : "";
        logger.info("[CHAT] 收到请求 sessionId={}, userId={}, messageLength={}",
                sessionId, userId, message.length());

        // 持久化用户消息
        try {
            chatHistoryService.saveUserMessage(sessionId, userId, message);
        } catch (Exception e) {
            logger.warn("[CHAT] 保存用户消息失败 sessionId={}: {}", sessionId, e.getMessage());
        }

        // 优雅中断本节点及集群中可能残留的同一 session 执行流
        agentExecutor.interruptPrevious(sessionId);

        SseEmitter emitter = agentExecutor.createEmitter();

        Msg msg = Msg.builder()
                .role(MsgRole.USER)
                .name("user")
                .content(TextBlock.builder().text(message).build())
                .metadata(Map.of("sessionId", sessionId, "userId", userId))
                .build();
        List<Msg> inputMessages = List.of(msg);

        // 判断是否为 continuation（继续上一轮对话的简单信号）
        String activeAgent = activeAgentSessionStore.getActiveAgent(sessionId);
        if (activeAgent != null && isContinuation(message)) {
            ReActAgent targetAgent = agentRegistry.getAgent(activeAgent);
            agentExecutor.executeAgent(targetAgent, inputMessages, message, emitter, sessionId, userId);
            return emitter;
        }

        // 新会话、切换话题或未命中 continuation：由 MasterAgent 协调
        logger.info("[CHAT] 会话 {} 由 MasterAgent 进行协调", sessionId);
        agentExecutor.executeAgent(null, inputMessages, message, emitter, sessionId, userId);
        return emitter;
    }

    /**
     * 打断当前 session 正在执行的 Agent 回复。
     */
    @PostMapping("/{sessionId}/interrupt")
    @SaCheckLogin
    public ResponseEntity<Map<String, Object>> interrupt(@PathVariable String sessionId) {
        String userId = StpUtil.getLoginIdAsString();
        logger.info("[CHAT] 收到打断请求 sessionId={}, userId={}", sessionId, userId);
        boolean interrupted = agentExecutor.interrupt(sessionId);
        return ResponseEntity.ok(Map.of("interrupted", interrupted));
    }

    /**
     * 用户回复 Agent 主动提问（Tool Suspend 恢复）。
     */
    @PostMapping("/respond")
    @SaCheckLogin
    public SseEmitter respond(@RequestBody ChatRespondRequest request) {
        String userId = StpUtil.getLoginIdAsString();
        String sessionId = request.getSessionId();
        AgentSessionContextHolder.set(new AgentSessionContext(userId, sessionId));

        logger.info("[CHAT] 收到用户回复 sessionId={}, userId={}, toolUseId={}",
                sessionId, userId, request.getToolUseId());

        return agentExecutor.resume(sessionId, userId, request.getToolUseId(), request.getResponse());
    }

    // ===================== 会话管理接口 =====================

    /**
     * 获取当前登录用户的所有历史会话。
     */
    @GetMapping("/conversations")
    @SaCheckLogin
    public List<ChatHistoryService.ConversationView> listConversations() {
        String userId = StpUtil.getLoginIdAsString();
        return chatHistoryService.listConversations(userId);
    }

    /**
     * 获取指定会话的历史消息。
     */
    @GetMapping("/{sessionId}/messages")
    @SaCheckLogin
    public List<ChatHistoryService.MessageView> listMessages(@PathVariable String sessionId) {
        String userId = StpUtil.getLoginIdAsString();
        return chatHistoryService.listMessages(sessionId, userId);
    }

    /**
     * 删除指定会话及其消息。
     */
    @DeleteMapping("/{sessionId}")
    @SaCheckLogin
    public ResponseEntity<Map<String, Object>> deleteConversation(@PathVariable String sessionId) {
        String userId = StpUtil.getLoginIdAsString();
        chatHistoryService.deleteConversation(sessionId, userId);
        return ResponseEntity.ok(Map.of("deleted", true));
    }

    /**
     * 更新会话标题。
     */
    @PutMapping("/{sessionId}/title")
    @SaCheckLogin
    public ResponseEntity<Map<String, Object>> updateTitle(@PathVariable String sessionId,
                                                           @RequestBody UpdateTitleRequest request) {
        String userId = StpUtil.getLoginIdAsString();
        chatHistoryService.updateTitle(sessionId, userId, request.title());
        return ResponseEntity.ok(Map.of("updated", true));
    }

    private record UpdateTitleRequest(String title) {
    }

    /**
     * 提交/更新对某条 AI 回复的用户反馈（点赞 / 点踩 / 取消）。
     */
    @PutMapping("/{sessionId}/messages/{messageId}/feedback")
    @SaCheckLogin
    public ResponseEntity<Map<String, Object>> updateFeedback(@PathVariable String sessionId,
                                                              @PathVariable String messageId,
                                                              @RequestBody(required = false) FeedbackRequest request) {
        String userId = StpUtil.getLoginIdAsString();
        String feedback = request == null ? null : request.feedback();
        chatHistoryService.updateFeedback(messageId, userId, feedback);
        logger.info("[CHAT] 更新消息反馈 sessionId={}, messageId={}, userId={}, feedback={}",
                sessionId, messageId, userId, feedback);
        return ResponseEntity.ok(Map.of("updated", true));
    }

    private record FeedbackRequest(String feedback) {
    }

    // ===================== 辅助方法 =====================

    private boolean isContinuation(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String lower = message.toLowerCase();

        boolean hasTopicSwitch = TOPIC_SWITCH_SIGNALS.stream().anyMatch(lower::equals);
        if (hasTopicSwitch) {
            return false;
        }

        return ContinuationSignals.ALL.stream().anyMatch(lower::equals);
    }
}
