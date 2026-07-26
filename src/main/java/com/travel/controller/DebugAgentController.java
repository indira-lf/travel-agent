package com.travel.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.travel.agent.context.AgentSessionContext;
import com.travel.agent.context.AgentSessionContextHolder;
import com.travel.agent.registry.AgentExecutionRegistry;
import com.travel.agent.registry.SseEmitterRegistry;
import com.travel.agent.session.AgentSession;
import com.travel.agent.session.AgentSessionManager;
import com.travel.agent.session.PendingToolSessionStore;
import com.travel.agent.tools.UserInteractionTools;
import com.travel.business.chat.service.ChatHistoryService;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.util.context.Context;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 【调试后门】直连子智能体接口。
 *
 * <p>绕过 MasterAgent 路由与意图识别流水线，直接与指定子智能体单独对话，
 * 便于开发期调试各子智能体的独立行为（prompt / 工具调用 / 输出格式）。</p>
 *
 * <p><b>安全提示：</b>此接口仅供调试使用，生产环境应通过配置或网关关闭。
 * 与 {@link ChatController} 共享同一套 SSE 事件契约（message / error /
 * user_interaction / interrupted），因此前端可复用 {@code /api/chat/respond}
 * 与 {@code /api/chat/{sessionId}/interrupt} 完成 HITL 续跑与中断。</p>
 *
 * @author Hollis
 */
@RestController
@RequestMapping("/api/debug/agent")
@CrossOrigin(origins = "*")
public class DebugAgentController {

    private static final Logger logger = LoggerFactory.getLogger(DebugAgentController.class);

    /**
     * 允许直连的子智能体白名单：PascalCase 名（= 首字母小写后的 Spring beanName） → 中文标签。
     * 使用有序 Map 保证前端下拉展示顺序稳定。
     */
    private static final Map<String, String> ALLOWED_AGENTS = new LinkedHashMap<>();

    static {
        ALLOWED_AGENTS.put("QueryRewritingAgent", "问题改写");
        ALLOWED_AGENTS.put("IntentRecognitionAgent", "意图识别");
        ALLOWED_AGENTS.put("InfoAgent", "信息查询");
        ALLOWED_AGENTS.put("ItineraryPlanAgent", "行程规划");
        ALLOWED_AGENTS.put("ItineraryReviewAgent", "行程审核");
        ALLOWED_AGENTS.put("ItineraryManageAgent", "行程管理");
    }

    @Autowired
    private ApplicationContext applicationContext;
    @Autowired
    private SseEmitterRegistry emitterRegistry;
    @Autowired
    private AgentExecutionRegistry executionRegistry;
    @Autowired
    private AgentSessionManager agentSessionManager;
    @Autowired
    private PendingToolSessionStore pendingToolSessionStore;
    @Autowired
    private ChatHistoryService chatHistoryService;

    /**
     * 返回可直连的子智能体列表，供前端下拉选择。
     */
    @GetMapping("/list")
    @SaCheckLogin
    public List<Map<String, String>> listAgents() {
        List<Map<String, String>> list = new ArrayList<>();
        ALLOWED_AGENTS.forEach((name, label) -> list.add(Map.of("name", name, "label", label)));
        return list;
    }

    /**
     * 直连指定子智能体对话（SSE 流式）。
     *
     * @param agentName 白名单内的 PascalCase 智能体名
     */
    @PostMapping("/{agentName}")
    @SaCheckLogin
    public SseEmitter chat(@PathVariable String agentName, @RequestBody DebugChatRequest request) {
        String userId = StpUtil.getLoginIdAsString();
        // 独立 sessionId，隔离真实会话历史，避免污染 chat 上下文
        String sessionId = request.getSessionId() != null && !request.getSessionId().isBlank()
                ? request.getSessionId()
                : "debug-" + agentName + "-" + userId;

        SseEmitter emitter = new SseEmitter(1_800_000L);

        if (!ALLOWED_AGENTS.containsKey(agentName)) {
            sendError(emitter, "不允许直连的智能体: " + agentName);
            return emitter;
        }

        AgentSessionContextHolder.set(new AgentSessionContext(userId, sessionId));

        String message = request.getMessage() != null ? request.getMessage() : "";
        logger.info("[DEBUG_AGENT] 直连 agent={}, sessionId={}, userId={}, messageLength={}",
                agentName, sessionId, userId, message.length());

        // 持久化用户消息与会话（与 ChatController 一致，直连入口同样保存对话记录）
        try {
            chatHistoryService.saveUserMessage(sessionId, userId, message);
        } catch (Exception e) {
            logger.warn("[DEBUG_AGENT] 保存用户消息失败 sessionId={}: {}", sessionId, e.getMessage());
        }

        // 先中断本 session 可能残留的执行流
        executionRegistry.interruptLocal(sessionId);

        // 解析 bean：ReActAgent 与 IntentRecognitionAgent(AgentBase) 均实现 Agent 接口，
        // 用共同基类型解析可同时兼容两者。必须在 getBean 前设置 ContextHolder，
        // 因为部分 Agent 在 build 时通过 createToolCtx 读取会话上下文。
        String beanName = Character.toLowerCase(agentName.charAt(0)) + agentName.substring(1);
        Agent agent;
        try {
            agent = applicationContext.getBean(beanName, Agent.class);
        } catch (Exception e) {
            logger.error("[DEBUG_AGENT] 获取 Agent bean 失败: name={}, beanName={}", agentName, beanName, e);
            sendError(emitter, "无法实例化智能体: " + agentName);
            return emitter;
        }

        Map<String, Object> context = Map.of("sessionId", sessionId, "userId", userId);
        Msg msg = Msg.builder()
                .role(MsgRole.USER)
                .name("user")
                .content(TextBlock.builder().text(message).build())
                .metadata(context)
                .build();

        emitterRegistry.register(sessionId, emitter);
        executionRegistry.register(sessionId, agent);
        AtomicBoolean suspended = new AtomicBoolean(false);
        final Agent finalAgent = agent;

        agent.call(List.of(msg))
                .contextWrite(Context.of("sessionId", sessionId, "userId", userId))
                .doFinally(signal -> executionRegistry.remove(sessionId))
                .subscribe(
                        result -> suspended.set(handleAgentResult(finalAgent, result, emitter, sessionId, userId)),
                        error -> {
                            logger.error("[DEBUG_AGENT] 直连执行失败 sessionId={}: {}",
                                    sessionId, error.getMessage(), error);
                            agentSessionManager.remove(sessionId);
                            pendingToolSessionStore.clear(sessionId);
                            sendError(emitter, error.getMessage());
                        },
                        () -> {
                            logger.info("[DEBUG_AGENT] 直连 SSE 流完成 sessionId={}", sessionId);
                            if (!suspended.get()) {
                                agentSessionManager.remove(sessionId);
                                pendingToolSessionStore.clear(sessionId);
                            }
                            emitter.complete();
                        });
        return emitter;
    }

    /**
     * 处理直连执行结果：TOOL_SUSPENDED 时注册 pending 并推送 user_interaction 事件
     * （复用 {@code /api/chat/respond} 续跑）；否则直接推送最终文本。
     *
     * @return true 表示当前结果触发了暂停，需要等待用户回复
     */
    private boolean handleAgentResult(Agent agent, Msg result, SseEmitter emitter, String sessionId, String userId) {
        if (result.getGenerateReason() == GenerateReason.TOOL_SUSPENDED && agent instanceof ReActAgent reAct) {
            ToolUseBlock toolUse = extractAskUserToolUse(result);
            if (toolUse != null) {
                logger.info("[DEBUG_AGENT] 触发工具调用暂停, sessionId={}, agent={}, toolUseId={}",
                        sessionId, agent.getName(), toolUse.getId());
                agentSessionManager.register(new AgentSession(sessionId, reAct, toolUse.getId(), null));
                pendingToolSessionStore.save(sessionId, agent.getName(), toolUse.getId(),
                        UserInteractionTools.TOOL_NAME);
                sendUserInteractionEvent(emitter, toolUse, sessionId);
                return true;
            }
        }
        // 调试后门：直接返回原始文本，便于观察子智能体真实输出（不做脱敏）
        String text = result.getTextContent();
        // 持久化 AI 回复（直连入口同样保存对话记录）
        try {
            chatHistoryService.saveAssistantMessage(sessionId, userId, text, agent.getName(), null);
        } catch (Exception e) {
            logger.warn("[DEBUG_AGENT] 保存AI回复失败 sessionId={}: {}", sessionId, e.getMessage());
        }
        sendMessage(emitter, text);
        return false;
    }

    private ToolUseBlock extractAskUserToolUse(Msg result) {
        if (result == null) {
            return null;
        }
        return result.getContentBlocks(ToolUseBlock.class).stream()
                .filter(t -> UserInteractionTools.TOOL_NAME.equals(t.getName()))
                .findFirst()
                .orElse(null);
    }

    private void sendUserInteractionEvent(SseEmitter emitter, ToolUseBlock toolUse, String sessionId) {
        try {
            JSONObject payload = JSON.parseObject(JSON.toJSONString(toolUse.getInput()));
            payload.put("toolUseId", toolUse.getId());
            emitter.send(SseEmitter.event().name("user_interaction").data(payload.toJSONString()));
            logger.info("[DEBUG_AGENT] 发送 user_interaction 事件 sessionId={}, toolUseId={}", sessionId, toolUse.getId());
        } catch (IllegalStateException e) {
            logger.debug("[DEBUG_AGENT] 无法发送 user_interaction 事件，emitter 已完成: {}", e.getMessage());
        } catch (IOException e) {
            logger.error("[DEBUG_AGENT] 发送 user_interaction 事件失败 sessionId={}", sessionId, e);
        }
    }

    private void sendMessage(SseEmitter emitter, String text) {
        try {
            emitter.send(SseEmitter.event().name("message").data(text == null ? "" : text));
        } catch (IllegalStateException e) {
            logger.debug("[DEBUG_AGENT] 无法发送消息，emitter 已完成: {}", e.getMessage());
        } catch (IOException e) {
            logger.error("[DEBUG_AGENT] 发送 SSE 消息失败", e);
            try {
                emitter.completeWithError(e);
            } catch (Exception ignored) {
            }
        }
    }

    private void sendError(SseEmitter emitter, String errorMessage) {
        try {
            emitter.send(SseEmitter.event().name("error").data(errorMessage == null ? "执行失败" : errorMessage));
            emitter.complete();
        } catch (IllegalStateException e) {
            logger.debug("[DEBUG_AGENT] 无法发送错误消息，emitter 已完成: {}", e.getMessage());
        } catch (IOException e) {
            try {
                emitter.completeWithError(e);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 直连调试请求体。
     */
    public static class DebugChatRequest {
        private String sessionId;
        private String message;

        public String getSessionId() {
            return sessionId;
        }

        public void setSessionId(String sessionId) {
            this.sessionId = sessionId;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }
    }
}
