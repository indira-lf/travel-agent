package com.travel.agent.hook;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 技能正文折叠 Hook。
 *
 * <p>SkillBox 的 {@code load_skill_through_path} 工具会把整份 SKILL.md（数百行）作为工具结果
 * 写入 memory。由于 ReActAgent 每轮 reasoning 都会重放整个 memory，这份静态的技能正文会在
 * 整个会话里被反复完整发送给 LLM，白白消耗 token。</p>
 *
 * <p>本 Hook 监听 {@link PreReasoningEvent}，<b>仅改写本轮发送给 LLM 的输入消息</b>
 * （{@link PreReasoningEvent#setInputMessages(List)}），<b>不触碰 memory 原文</b>——因此完全无状态、
 * 无副作用：下一轮框架仍从 memory 拿到完整消息，本 Hook 再次按需折叠。</p>
 *
 * <p><b>折叠策略：</b>技能正文在其后已积累足够交互（即 LLM 已据此完成若干步骤）时，说明它已
 * 完成使命，可折叠为占位符；占位符会提示 LLM 若仍需完整说明可再次 {@code load_skill_through_path}。
 * 刚加载后的近几条消息保持完整，保证 LLM 加载后能立即使用。</p>
 *
 * @author Hollis
 */
@Component
public class SkillContentCollapseHook implements Hook {

    private static final Logger logger = LoggerFactory.getLogger(SkillContentCollapseHook.class);

    /** 技能加载工具名（SkillBox 内置）。 */
    private static final String LOAD_SKILL_TOOL = "load_skill_through_path";

    /**
     * 技能正文之后至少还有这么多条消息时才折叠。
     * 保证「刚加载完」的近几轮 reasoning 仍能看到完整正文，避免加载后立刻失去说明。
     * 提示词中增加懒加载指令：**懒加载搜索技能（重要）** xxxx
     */
    private static final int KEEP_RECENT_MSGS = 6;

    @Override
    public int priority() {
        // 在日志 Hook（默认 100）之前执行，使执行日志反映折叠后的真实 LLM 输入
        return 6;
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
        if (messages == null || messages.size() <= KEEP_RECENT_MSGS) {
            return Mono.just(event);
        }

        // 收集技能加载调用的 toolUseId -> 重新加载提示（skillId/path），供占位符引导 LLM 重新拉取
        Map<String, String> reloadHints = collectReloadHints(messages);
        if (reloadHints.isEmpty()) {
            return Mono.just(event);
        }

        String agentName = event.getAgent() != null ? event.getAgent().getName() : null;
        List<Msg> newMessages = new ArrayList<>(messages.size());
        boolean changed = false;
        long savedChars = 0L;

        for (int i = 0; i < messages.size(); i++) {
            Msg msg = messages.get(i);
            ToolResultBlock skillResult = extractSkillLoadResult(msg);
            int msgsAfter = messages.size() - 1 - i;
            if (skillResult != null && msgsAfter >= KEEP_RECENT_MSGS) {
                int originalLen = textLength(skillResult);
                String hint = reloadHints.getOrDefault(skillResult.getId(), defaultReloadHint());
                String placeholder = buildPlaceholder(hint);
                if (originalLen > placeholder.length()) {
                    ToolResultBlock folded = ToolResultBlock.of(
                            skillResult.getId(),
                            skillResult.getName(),
                            TextBlock.builder().text(placeholder).build());
                    newMessages.add(Msg.builder()
                            .name(agentName)
                            .role(MsgRole.TOOL)
                            .content(folded)
                            .build());
                    savedChars += (long) originalLen - placeholder.length();
                    changed = true;
                    continue;
                }
            }
            newMessages.add(msg);
        }

        if (!changed) {
            return Mono.just(event);
        }

        event.setInputMessages(newMessages);
        logger.info("[SKILL_COLLAPSE] 已折叠历史技能正文, 约节省 {} 字符", savedChars);
        return Mono.just(event);
    }

    /**
     * 遍历消息，收集所有 {@code load_skill_through_path} 工具调用的重新加载提示，
     * 以 toolUseId 为键，便于对应的工具结果被折叠时生成精确的重载指引。
     */
    private Map<String, String> collectReloadHints(List<Msg> messages) {
        Map<String, String> hints = new HashMap<>();
        for (Msg msg : messages) {
            List<ContentBlock> blocks = msg.getContent();
            if (blocks == null) {
                continue;
            }
            for (ContentBlock block : blocks) {
                if (block instanceof ToolUseBlock toolUse && LOAD_SKILL_TOOL.equals(toolUse.getName())) {
                    hints.put(toolUse.getId(), buildReloadHint(toolUse));
                }
            }
        }
        return hints;
    }

    /**
     * 若消息是 {@code load_skill_through_path} 的工具结果，返回其 {@link ToolResultBlock}，否则返回 {@code null}。
     */
    private ToolResultBlock extractSkillLoadResult(Msg msg) {
        List<ContentBlock> blocks = msg.getContent();
        if (blocks == null) {
            return null;
        }
        for (ContentBlock block : blocks) {
            if (block instanceof ToolResultBlock result && LOAD_SKILL_TOOL.equals(result.getName())) {
                return result;
            }
        }
        return null;
    }

    /** 统计工具结果里所有 TextBlock 的文本总长度。 */
    private int textLength(ToolResultBlock result) {
        int len = 0;
        List<ContentBlock> output = result.getOutput();
        if (output != null) {
            for (ContentBlock block : output) {
                if (block instanceof TextBlock textBlock && textBlock.getText() != null) {
                    len += textBlock.getText().length();
                }
            }
        }
        return len;
    }

    /** 从技能加载调用参数中解析出 skillId/path，拼成可直接复用的重载指引。 */
    private String buildReloadHint(ToolUseBlock toolUse) {
        try {
            Object raw = toolUse.getInput();
            if (raw != null) {
                JSONObject json = (raw instanceof String)
                        ? JSON.parseObject((String) raw)
                        : JSON.parseObject(JSON.toJSONString(raw));
                String skillId = json.getString("skillId");
                if (skillId == null) {
                    skillId = json.getString("skill_id");
                }
                String path = json.getString("path");
                if (skillId != null) {
                    String p = (path != null && !path.isBlank()) ? path : "SKILL.md";
                    return String.format("load_skill_through_path(skillId=\"%s\", path=\"%s\")", skillId, p);
                }
            }
        } catch (Exception e) {
            logger.debug("[SKILL_COLLAPSE] 解析 load_skill_through_path 参数失败: {}", e.getMessage());
        }
        return defaultReloadHint();
    }

    private String defaultReloadHint() {
        return "load_skill_through_path(skillId=\"<skill-id>\", path=\"SKILL.md\")";
    }

    /** 折叠占位符：明确告知正文已省略，并给出精确的重载指引。 */
    private String buildPlaceholder(String reloadHint) {
        return "[技能文档正文已省略以节省上下文] 该技能的说明此前已加载，你应已据此完成相应操作。"
                + "如仍需查阅完整说明，请重新调用：" + reloadHint;
    }
}
