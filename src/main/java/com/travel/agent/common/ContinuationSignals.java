package com.travel.agent.common;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 用户在子智能体交互中可以直接发送的"继续/中止"信号词。
 *
 * <p>统一维护点：
 * <ul>
 *   <li>{@link com.travel.controller.ChatController} 命中这些词时，跳过 MasterAgent 协调，
 *       直接复用 activeAgent 续跑，避免主智能体重复调度。</li>
 *   <li>{@link com.travel.agent.QuestionRecommendationAgent} 在生成推荐问题时，
 *       优先从这些词中挑选至少 1 个作为"快速操作"选项，让用户点击后能直接触发对应动作。</li>
 * </ul>
 *
 * <p>新增关键词时请同步考虑它在 ChatController 续跑语义与推荐问题中的双重作用。</p>
 *
 * @author Hollis
 */
public final class ContinuationSignals {

    private ContinuationSignals() {
    }

    /** 全部扁平化的继续信号词集合（小写形式，"ok/yes/confirm/continue" 已统一为小写） */
    public static final Set<String> ALL = Set.of(
            "确定", "确认", "提交", "继续", "是的", "好的", "对", "好", "修改", "补充", "不对", "取消", "重新", "再",
            "ok", "yes", "confirm", "continue", "修改一下", "补充一下", "重新来", "再来");

    /** 按语义分组的继续信号词，便于在 prompt 中以可读形式呈现 */
    public static final Map<String, List<String>> GROUPS = Map.of(
            "确认/继续类", List.of("确定", "确认", "提交", "继续", "是的", "好的", "对", "好", "ok", "yes", "confirm", "continue"),
            "修改/补充类", List.of("修改", "补充", "修改一下", "补充一下"),
            "取消/重新类", List.of("不对", "取消", "重新", "重新来", "再来", "再"));

    /**
     * 返回按"语义分组 → 词列表"格式化的可读字符串，供 prompt 注入。
     *
     * <p>示例：</p>
     * <pre>
     * - 确认/继续类：确定、确认、提交、继续、是的、好的、对、好、ok、yes、confirm、continue
     * - 修改/补充类：修改、补充、修改一下、补充一下
     * - 取消/重新类：不对、取消、重新、重新来、再来、再
     * - 需要类：需要
     * </pre>
     */
    public static String asGroupedPrompt() {
        StringBuilder sb = new StringBuilder();
        GROUPS.forEach((group, words) -> {
            sb.append("- ").append(group).append("：")
                    .append(String.join("、", words)).append("\n");
        });
        return sb.toString();
    }
}
