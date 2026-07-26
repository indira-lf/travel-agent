package com.travel.agent;

import com.travel.agent.common.PromptLoader;
import com.travel.agent.hook.ActiveAgentPersistenceHook;
import com.travel.agent.hook.AgentExecutionLoggerHook;
import com.travel.agent.hook.ProgressNotifierHook;
import com.travel.agent.hook.SessionPersistenceHook;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.interruption.InterruptContext;
import io.agentscope.core.message.*;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/**
 * 问题改写 Agent（单次 LLM 调用，继承 AgentBase）。
 *
 * <p>结合 SessionPersistenceHook 自动加载的多轮对话历史，对当前问题进行
 * 指代消除、错别字修正和上下文融合，输出一句自包含的改写结果 JSON。</p>
 *
 * <p>本类不使用 ReActAgent：问题改写是确定性的单步文本变换，无需工具调用、
 * 无需多轮推理循环。直接通过 {@link Model#stream} 发起单次 LLM 请求即可。</p>
 *
 * @author Hollis
 */
@Component("queryRewritingAgent")
@Scope("prototype")
public class QueryRewritingAgent extends AgentBase {

    private static final Logger logger = LoggerFactory.getLogger(QueryRewritingAgent.class);

    public static final String NAME = "QueryRewritingAgent";

    private final Model stableModel;
    private final String sysPrompt;

    public QueryRewritingAgent(
            @Qualifier("stableModel") Model stableModel,
            AgentExecutionLoggerHook executionLoggerHook,
            ProgressNotifierHook progressNotifierHook,
            SessionPersistenceHook sessionPersistenceHook,
            ActiveAgentPersistenceHook activeAgentPersistenceHook) {
        super(NAME, "多轮对话用户问题改写与指代消除", true,
                List.of(executionLoggerHook, progressNotifierHook, sessionPersistenceHook, activeAgentPersistenceHook));
        this.stableModel = stableModel;
        this.sysPrompt = PromptLoader.load("query-rewriting-agent-system.md");
    }

    @Override
    protected Mono<Msg> doCall(List<Msg> msgs) {
        List<Msg> messages = new ArrayList<>();
        messages.add(Msg.builder()
                .role(MsgRole.SYSTEM)
                .name("system")
                .content(TextBlock.builder().text(sysPrompt).build())
                .build());
        if (msgs != null) {
            messages.addAll(msgs);
        }

        return Mono.fromCallable(() -> stableModel.stream(messages, null, null).collectList().block())
                .map(responses -> Msg.builder()
                        .name(NAME)
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text(extractText(responses)).build())
                        .build());
    }

    @Override
    protected Mono<Msg> handleInterrupt(InterruptContext context, Msg... originalArgs) {
        return Mono.just(Msg.builder()
                .name(NAME)
                .role(MsgRole.ASSISTANT)
                .content(TextBlock.builder().text("已停止问题改写。").build())
                .generateReason(GenerateReason.INTERRUPTED)
                .build());
    }

    @Override
    protected Mono<Void> doObserve(Msg msg) {
        return Mono.empty();
    }

    // ============== 工具方法 ==============

    /**
     * 将流式 {@link ChatResponse} 中的所有 TextBlock 文本按顺序拼接为完整字符串。
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
}
