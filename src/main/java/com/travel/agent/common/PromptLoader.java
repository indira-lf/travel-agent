package com.travel.agent.common;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;

/**
 * @author Hollis
 */
public final class PromptLoader {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    private PromptLoader() {
    }

    /**
     * 加载 prompt 模板并注入所有动态变量（含 current_time）。
     * <p>兼容旧用法，不需要缓存优化的场景仍可使用此方法。</p>
     */
    public static String load(String filename) {
        String content = loadResource("prompts/" + filename);
        if (content == null) {
            content = "You are a helpful travel assistant.";
        }
        LocalDate today = LocalDate.now();
        // 直接注入权威的"星期几"，避免 LLM 从日期自行推算星期导致"本周"范围算错
        String weekday = today.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.CHINA);
        return content
                .replace("{{current_date}}", today.format(DATE_FORMATTER))
                .replace("{{current_weekday}}", weekday)
                .replace("{{current_time}}", LocalTime.now().format(TIME_FORMATTER));
    }

    /**
     * 静态加载 prompt 模板：仅注入 current_date 和 current_weekday（每日变化一次），
     * 不注入 current_time，以保持 system prompt 前缀在同一天内完全稳定，
     * 利于百炼隐式缓存命中（相同前缀按 20% 计费）。
     * <p>动态时间由 {@code DynamicTimeInjectionHook} 作为独立 system message 注入。</p>
     */
    public static String loadStatic(String filename) {
        String content = loadResource("prompts/" + filename);
        if (content == null) {
            content = "You are a helpful travel assistant.";
        }
        LocalDate today = LocalDate.now();
        String weekday = today.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.CHINA);
        return content
                .replace("{{current_date}}", today.format(DATE_FORMATTER))
                .replace("{{current_weekday}}", weekday)
                .replace("{{current_time}}", "（见下方动态注入）");
    }

    /**
     * 构建动态时间上下文文本，供 {@code DynamicTimeInjectionHook} 作为独立 system message 注入。
     * <p>放在 system prompt 之后，不影响前缀缓存匹配。</p>
     */
    public static String buildTimeContext() {
        return "当前时间：" + LocalTime.now().format(TIME_FORMATTER);
    }

    private static String loadResource(String path) {
        try (var is = PromptLoader.class.getClassLoader().getResourceAsStream(path)) {
            if (is == null) {
                return "";
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}
