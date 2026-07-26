package com.gogo.travel.agent.hook;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * 记录 Agent 执行全链路关键节点，方便调试 Agent 推理与工具调用过程。
 *
 * @author Hollis
 */
public class AgentExecutionLoggerHook implements Hook {

    private static final Logger logger = LoggerFactory.getLogger(AgentExecutionLoggerHook.class);

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        String agentName = getAgentName(event);

        if (event instanceof PreCallEvent preCall) {
            logger.info("[AGENT][{}] PreCall 输入消息数={}", agentName, preCall.getInputMessages().size());
            if (logger.isDebugEnabled()) {
                logMessages(preCall.getInputMessages(), agentName);
            }
        } else if (event instanceof PreReasoningEvent preReasoning) {
            java.util.List<Msg> inputs = preReasoning.getInputMessages();
            logger.info("[AGENT][{}] PreReasoning 输入消息数={}", agentName, inputs.size());
            // 输出本次 reasoning 调 LLM 时传入的完整消息内容，便于分析 token 组成与做优化
            if (logger.isDebugEnabled()) {
                logReasoningMessages(inputs, agentName);
            }
        } else if (event instanceof PostReasoningEvent postReasoning) {
            Msg reasoning = postReasoning.getReasoningMessage();
            logger.info("[AGENT][{}] PostReasoning 推理结果长度={}", agentName,
                    reasoning != null ? reasoning.getTextContent().length() : 0);
            if (logger.isDebugEnabled() && reasoning != null) {
                logger.debug("[AGENT][{}] reasoning={}", agentName, reasoning.getTextContent());
            }
        } else if (event instanceof PreActingEvent preActing) {
            ToolUseBlock toolUse = preActing.getToolUse();
            if (toolUse != null) {
                logger.info("[AGENT][{}] PreActing 工具调用 toolName={}, toolUseId={}",
                        agentName, toolUse.getName(), toolUse.getId());
                if (logger.isDebugEnabled()) {
                    logger.debug("[AGENT][{}] toolInput={}", agentName, toolUse.getInput());
                }
            }
        } else if (event instanceof PostActingEvent postActing) {
            ToolUseBlock toolUse = postActing.getToolUse();
            ToolResultBlock toolResult = postActing.getToolResult();
            if (toolUse != null) {
                boolean suspended = toolResult != null && toolResult.isSuspended();
                logger.info("[AGENT][{}] PostActing 工具调用完成 toolName={}, toolUseId={}, suspended={}",
                        agentName, toolUse.getName(), toolUse.getId(), suspended);
                if (logger.isDebugEnabled() && toolResult != null) {
                    logger.debug("[AGENT][{}] toolResult output size={} , content = {}", agentName, toolResult.getOutput().size(),toolResult.getOutput());
                }
            }
        } else if (event instanceof PostCallEvent postCall) {
            Msg finalMsg = postCall.getFinalMessage();
            logger.info("[AGENT][{}] PostCall 最终回复长度={}", agentName,
                    finalMsg != null ? finalMsg.getTextContent().length() : 0);
        }

        return Mono.just(event);
    }

    private String getAgentName(HookEvent event) {
        Agent agent = event.getAgent();
        return agent != null ? agent.getName() : "unknown";
    }

    private void logMessages(java.util.List<Msg> messages, String agentName) {
        for (int i = 0; i < messages.size(); i++) {
            Msg msg = messages.get(i);
            logger.debug("[AGENT][{}] msg[{}] role={} content={}",
                    agentName, i, msg.getRole(), msg.getTextContent());
        }
    }

    /**
     * 输出每一次 reasoning 调 LLM 时传入的完整消息内容（含系统/用户/工具调用/工具结果），
     * 并统计每条及合计字符数，方便定位上下文膨胀、做 token 优化。
     */
    private void logReasoningMessages(java.util.List<Msg> messages, String agentName) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        int totalChars = 0;
        StringBuilder sb = new StringBuilder();
        sb.append("\n===== [AGENT][").append(agentName).append("] LLM 输入消息（共 ")
          .append(messages.size()).append(" 条）=====");
        for (int i = 0; i < messages.size(); i++) {
            Msg msg = messages.get(i);
            String rendered = renderMsgContent(msg);
            totalChars += rendered.length();
            sb.append("\n--- msg[").append(i).append("] role=").append(msg.getRole())
              .append(", name=").append(msg.getName())
              .append(", chars=").append(rendered.length()).append(" ---\n")
              .append(rendered);
        }
        sb.append("\n===== [AGENT][").append(agentName).append("] LLM 输入合计字符数=")
          .append(totalChars).append(" =====");
        logger.debug(sb.toString());
    }

    /** 渲染一条消息的完整内容，覆盖文本 / 工具调用 / 工具结果块。 */
    private String renderMsgContent(Msg msg) {
        java.util.List<ContentBlock> blocks = msg.getContent();
        if (blocks == null || blocks.isEmpty()) {
            return msg.getTextContent() != null ? msg.getTextContent() : "";
        }
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock text) {
                sb.append(text.getText());
            } else if (block instanceof ToolUseBlock toolUse) {
                sb.append("[tool_use name=").append(toolUse.getName())
                  .append(", id=").append(toolUse.getId())
                  .append(", input=").append(toolUse.getInput()).append("]");
            } else if (block instanceof ToolResultBlock toolResult) {
                sb.append("[tool_result id=").append(toolResult.getId())
                  .append(", output=").append(toolResult.getOutput()).append("]");
            } else {
                sb.append(block);
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}
