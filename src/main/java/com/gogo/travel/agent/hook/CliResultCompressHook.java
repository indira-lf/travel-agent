package com.gogo.travel.agent.hook;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolResultMessageBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * MCP/CLI 工具结果去重 Hook。
 *
 * <p>tuniu-cli 等 MCP 工具返回的 JSON 中，同一份业务数据会同时出现在
 * {@code content[0].text} 与 {@code structuredContent} 两处，内容逐字节相同，
 * 直接导致回填 memory 的工具结果体积翻倍、LLM 输入 token 膨胀。</p>
 *
 * <p>本 Hook 监听 {@link PostActingEvent}，在工具结果<b>回填 memory 之前</b>对结果文本
 * 做 JSON 级优化以压缩上下文：</p>
 * <ol>
 *   <li><b>去重：</b>凡是同时携带非空 {@code content} 数组与 {@code structuredContent} 的
 *       JSON 对象，均删除其 {@code structuredContent}，只保留 {@code content[0].text}；</li>
 *   <li><b>去空字段：</b>递归移除无值字段（null、空白字符串、空数组、空对象），
 *       数字 {@code 0} 与布尔 {@code false} 视为有效值予以保留；</li>
 *   <li><b>去冗余字段：</b>剔除对 LLM 决策无用、仅增大 token 的黑名单字段
 *       （如机票查询里的航司 logo 图片、内部 queryId、机型代码等）；</li>
 *   <li><b>压缩：</b>紧凑序列化，去除缩进/换行等冗余空白。</li>
 * </ol>
 *
 * <p><b>写回要点：</b>框架最终写入 memory 的是 {@link PostActingEvent#getToolResultMsg()}，
 * 因此本 Hook 同时重建 {@link ToolResultBlock} 与其对应的 {@link Msg}。</p>
 *
 * @author Hollis
 */
@Component
public class CliResultCompressHook implements Hook {

    private static final Logger logger = LoggerFactory.getLogger(CliResultCompressHook.class);

    private static final String CONTENT_KEY = "content";
    private static final String STRUCTURED_CONTENT_KEY = "structuredContent";

    /**
     * 无业务价值的冗余字段黑名单：对 LLM 决策无用、仅增大 token 的字段（如机票查询里的
     * 航司 logo 图片、内部 queryId、机型代码等），在清理时无条件剔除。
     */
    private static final java.util.Set<String> DROP_KEYS = java.util.Set.of(
            "airComImageUrl",
            "queryId",
            "craftType",
            "planModel");

    // execute_shell_command 将工具输出包装为 <returncode>..</returncode><stdout>JSON</stdout><stderr>..</stderr>
    private static final String STDOUT_OPEN = "<stdout>";
    private static final String STDOUT_CLOSE = "</stdout>";

    @Override
    public int priority() {
        // 尽量靠前执行（默认 100，RghUserIsolation=5），保证在进度推送与 memory 回填前完成去重
        return 4;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PostActingEvent postActing) {
            return handlePostActing(postActing).map(e -> (T) e);
        }
        return Mono.just(event);
    }

    private Mono<PostActingEvent> handlePostActing(PostActingEvent event) {
        ToolResultBlock toolResult = event.getToolResult();
        ToolUseBlock toolUse = event.getToolUse();
        if (toolResult == null || toolUse == null || toolResult.isSuspended()) {
            return Mono.just(event);
        }
        List<ContentBlock> output = toolResult.getOutput();
        if (output == null || output.isEmpty()) {
            return Mono.just(event);
        }

        logger.info("[MCP_DEDUP] 已优化工具结果 JSON（去重/去空字段/压缩");

        List<ContentBlock> newOutput = new ArrayList<>(output.size());
        boolean changed = false;
        long savedBytes = 0L;
        for (ContentBlock block : output) {
            if (block instanceof TextBlock textBlock) {
                String text = textBlock.getText();
                String deduped = optimizeText(text);
                if (deduped != null && !deduped.equals(text)) {
                    newOutput.add(TextBlock.builder().text(deduped).build());
                    savedBytes += (long) text.length() - deduped.length();
                    changed = true;
                    continue;
                }
            }
            newOutput.add(block);
        }

        if (!changed) {
            return Mono.just(event);
        }

        // 重建 ToolResultBlock（保留 id/name/metadata），供读取 getToolResult() 的 Hook 使用
        ToolResultBlock newResult = new ToolResultBlock(
                toolResult.getId(),
                toolResult.getName(),
                newOutput,
                toolResult.getMetadata());
        event.setToolResult(newResult);

        // 框架写入 memory 的是 toolResultMsg，必须一并重建，去重才会真正生效
        String agentName = event.getAgent() != null ? event.getAgent().getName() : null;
        Msg newMsg = ToolResultMessageBuilder.buildToolResultMsg(newResult, toolUse, agentName);
        event.setToolResultMsg(newMsg);

        logger.info("[MCP_DEDUP] 已优化工具结果 JSON（去重/去空字段/压缩）, tool={}, 约节省 {} 字节",
                toolUse.getName(), savedBytes);
        return Mono.just(event);
    }

    /**
     * 优化单个 TextBlock 文本：兼容 execute_shell_command 的
     * {@code <returncode>..</returncode><stdout>JSON</stdout><stderr>..</stderr>} 包装——
     * 仅抽取 {@code <stdout>} 内的 JSON 做优化后原样回填包装；无包装时直接按 JSON 优化。
     * 无 JSON 或无体积收益时返回 {@code null}。
     */
    private String optimizeText(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        int open = text.indexOf(STDOUT_OPEN);
        int close = text.lastIndexOf(STDOUT_CLOSE);
        if (open >= 0 && close > open) {
            int jsonStart = open + STDOUT_OPEN.length();
            String inner = text.substring(jsonStart, close);
            String optimizedInner = optimizeJson(inner);
            if (optimizedInner == null) {
                return null;
            }
            return text.substring(0, jsonStart) + optimizedInner + text.substring(close);
        }
        return optimizeJson(text);
    }

    /**
     * 对 JSON 文本做优化：去除重复 structuredContent、递归移除无值字段、
     * 下钻内嵌 JSON 字符串（如 {@code content[0].text}）并紧凑序列化。
     * 仅当优化后文本比原文更短时返回新文本；非 JSON 或无体积收益时返回 {@code null}。
     */
    private String optimizeJson(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String trimmed = text.trim();
        Object root;
        try {
            // 用 parse 而非 parseObject，兼容数组根等非对象根结构
            root = JSON.parse(trimmed);
        } catch (Exception ignored) {
            // 非 JSON 文本不处理，避免破坏原始内容
            return null;
        }
        if (root == null) {
            return null;
        }
        Object cleaned = cleanNode(root);
        // fastjson2 默认输出紧凑 JSON（无多余空白），天然完成压缩
        String compact = JSON.toJSONString(cleaned);
        // 无体积收益（原文已紧凑且无可删字段）时跳过重建
        if (compact.length() >= trimmed.length()) {
            return null;
        }
        return compact;
    }

    /**
     * 递归清理 JSON 节点并返回清理后的值：
     * <ul>
     *   <li>对象：删除重复 structuredContent 与无值字段，逐字段下钻；</li>
     *   <li>数组：逐项下钻；</li>
     *   <li>字符串：若本身是内嵌 JSON（如 {@code content[0].text}），解析后同样优化再回填。</li>
     * </ul>
     */
    private Object cleanNode(Object value) {
        if (value instanceof JSONObject obj) {
            cleanObject(obj);
            return obj;
        }
        if (value instanceof JSONArray arr) {
            for (int i = 0; i < arr.size(); i++) {
                arr.set(i, cleanNode(arr.get(i)));
            }
            return arr;
        }
        if (value instanceof String str) {
            return optimizeEmbeddedJson(str);
        }
        return value;
    }

    /**
     * 清理单个对象：先删除与非空 content 重复的 structuredContent，
     * 再逐字段下钻清理，最后移除变空/无值的字段。
     */
    private void cleanObject(JSONObject obj) {
        if (obj.containsKey(STRUCTURED_CONTENT_KEY) && isNonEmptyContentArray(obj.get(CONTENT_KEY))) {
            obj.remove(STRUCTURED_CONTENT_KEY);
        }
        obj.keySet().removeAll(DROP_KEYS);
        Iterator<Map.Entry<String, Object>> it = obj.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Object> entry = it.next();
            Object cleaned = cleanNode(entry.getValue());
            if (isEmptyValue(cleaned)) {
                it.remove();
            } else {
                entry.setValue(cleaned);
            }
        }
    }

    /**
     * 尝试将"疑似内嵌 JSON"的字符串解析、优化并紧凑序列化；
     * 非 JSON、解析失败或无体积收益时原样返回，确保不破坏普通文本。
     */
    private String optimizeEmbeddedJson(String str) {
        if (str == null) {
            return null;
        }
        String trimmed = str.trim();
        if (trimmed.length() < 2) {
            return str;
        }
        char first = trimmed.charAt(0);
        if (first != '{' && first != '[') {
            return str;
        }
        Object node;
        try {
            node = JSON.parse(trimmed);
        } catch (Exception ignored) {
            return str;
        }
        if (node == null) {
            return str;
        }
        Object cleaned = cleanNode(node);
        String compact = JSON.toJSONString(cleaned);
        return compact.length() < str.length() ? compact : str;
    }

    /**
     * 判断字段是否"无值"：null、空白字符串、空数组、空对象。
     * 注意：数字 0 与布尔 false 属于有效值，不会被移除。
     */
    private boolean isEmptyValue(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof CharSequence cs) {
            return cs.toString().isBlank();
        }
        if (value instanceof Map<?, ?> map) {
            return map.isEmpty();
        }
        if (value instanceof List<?> list) {
            return list.isEmpty();
        }
        return false;
    }

    /**
     * 判断 content 是否为非空数组（确保删除 structuredContent 后仍至少保留一份内容）。
     */
    private boolean isNonEmptyContentArray(Object content) {
        if (content instanceof JSONArray arr) {
            return !arr.isEmpty();
        }
        if (content instanceof List<?> list) {
            return !list.isEmpty();
        }
        if (content instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        return false;
    }
}
