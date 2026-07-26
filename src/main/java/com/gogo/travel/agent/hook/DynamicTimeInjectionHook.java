package com.gogo.travel.agent.hook;

import com.gogo.travel.agent.common.PromptLoader;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/**
 * 动态时间注入 Hook：在每轮 LLM 推理前，向 inputMessages 中插入一条包含当前时间的 system 消息。
 *
 * <p>目的：保持 system prompt（第一条 system message）文本在同一天内完全稳定，
 * 使百炼隐式缓存能够命中前缀（~4000 token 按 20% 计费），
 * 而动态时间作为第二条 system message 注入，不影响前缀匹配。</p>
 *
 * <p>注入位置：紧跟第一条 SYSTEM 角色消息之后（index=1）。
 * 仅当 messages 第一条是 SYSTEM 角色时才插入，否则跳过。</p>
 *
 * <p>优先级 = 1，在所有其他 Hook（包括 AutoContextHook）之前执行，
 * 确保时间信息在后续 Hook 处理之前就已就位。</p>
 *
 * @author Hollis
 */
@Component
public class DynamicTimeInjectionHook implements Hook {

    private static final Logger logger = LoggerFactory.getLogger(DynamicTimeInjectionHook.class);

    @Override
    public int priority() {
        // 最先执行，在 AutoContextHook（默认优先级）之前
        return 1;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PreReasoningEvent preReasoning) {
            return handle(preReasoning).map(e -> (T) e);
        }
        return Mono.just(event);
    }

    private Mono<PreReasoningEvent> handle(PreReasoningEvent event) {
        List<Msg> messages = event.getInputMessages();
        if (messages == null || messages.isEmpty()) {
            return Mono.just(event);
        }

        // 仅当第一条消息是 SYSTEM 角色时才注入
        Msg firstMsg = messages.get(0);
        if (firstMsg.getRole() != MsgRole.SYSTEM) {
            return Mono.just(event);
        }

        // 构建动态时间 system message
        String timeContext = PromptLoader.buildTimeContext();
        Msg timeMsg = Msg.builder()
                .role(MsgRole.SYSTEM)
                .content(TextBlock.builder().text(timeContext).build())
                .build();

        // 在第一条 system message 之后（index=1）插入
        List<Msg> newMessages = new ArrayList<>(messages.size() + 1);
        // 静态 system prompt
        newMessages.add(messages.get(0));
        // 动态时间
        newMessages.add(timeMsg);
        for (int i = 1; i < messages.size(); i++) {
            newMessages.add(messages.get(i));
        }

        event.setInputMessages(newMessages);
        logger.debug("[DYNAMIC_TIME] 已注入动态时间: {}", timeContext);
        return Mono.just(event);
    }
}
