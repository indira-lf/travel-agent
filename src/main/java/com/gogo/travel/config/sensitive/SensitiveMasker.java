package com.gogo.travel.config.sensitive;

import com.github.houbb.sensitive.api.IStrategy;
import com.github.houbb.sensitive.core.api.context.SensitiveContext;
import com.github.houbb.sensitive.core.api.strategory.StrategyCardId;
import com.github.houbb.sensitive.core.api.strategory.StrategyEmail;
import com.github.houbb.sensitive.core.api.strategory.StrategyIdNo;
import com.github.houbb.sensitive.core.api.strategory.StrategyMaskAll;
import com.github.houbb.sensitive.core.api.strategory.StrategyPhone;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 统一敏感信息脱敏工具。
 *
 * <p><strong>脱敏动作</strong>统一复用 houbb sensitive 框架内置的脱敏策略
 * （{@link StrategyPhone}/{@link StrategyIdNo}/{@link StrategyCardId}/{@link StrategyEmail}/
 * {@link StrategyMaskAll}），保证与框架的脱敏规则一致、口径统一。</p>
 *
 * <p>由于 houbb 框架的策略是<strong>值级脱敏</strong>（面向被注解的 Bean 字段整值），无法直接在自由文本
 * （日志消息、LLM 回复等）中识别敏感片段，因此本工具用<strong>精确、有边界</strong>的正则先在文本中
 * <em>定位</em>敏感片段，再把命中的整值交给框架策略完成脱敏。这样既保留了对自由文本的扫描能力，
 * 又把"如何打码"下沉到框架，避免自造掩码逻辑。</p>
 *
 * <p>本工具不依赖日志框架，可在日志脱敏（{@link SensitiveMaskingConverter}）与前端返回脱敏
 * （ChatController 输出、进度事件推送等）多处复用。</p>
 *
 * @author Hollis
 */
public final class SensitiveMasker {

    /** 中国大陆手机号：前后不能紧邻数字，避免命中长数字串的一部分。 */
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");

    /** 18 位身份证号（末位可能为 X）。 */
    private static final Pattern ID_CARD = Pattern.compile("(?<!\\d)\\d{17}[\\dXx](?!\\d)");

    /** 16~19 位银行卡号。 */
    private static final Pattern BANK_CARD = Pattern.compile("(?<!\\d)\\d{16,19}(?!\\d)");

    /** 邮箱地址。 */
    private static final Pattern EMAIL = Pattern.compile(
            "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    /** {@code sk-} 前缀的 API Key，如 DashScope 的 sk-xxxxxxxx。 */
    private static final Pattern SK_KEY = Pattern.compile("sk-[A-Za-z0-9]{6,}");

    /**
     * 键值对形态的秘钥：key 名命中 password/secret/token/api-key/access-key-secret 等，
     * 兼容 JSON（"key":"value"）、YAML/properties（key: value / key=value）等写法。
     */
    private static final Pattern KV_SECRET = Pattern.compile(
            "(?i)(password|passwd|pwd|secret|access[-_]?key[-_]?secret|access[-_]?key[-_]?id|api[-_]?key|apikey|access[-_]?token|token|authorization)"
                    + "(\"?\\s*[:=]\\s*\"?)"
                    + "([^\"'\\s,;)}\\]]+)");

    /** houbb sensitive 框架内置脱敏策略（无状态，可复用单例）。 */
    private static final IStrategy PHONE_STRATEGY = new StrategyPhone();
    private static final IStrategy ID_NO_STRATEGY = new StrategyIdNo();
    private static final IStrategy CARD_ID_STRATEGY = new StrategyCardId();
    private static final IStrategy EMAIL_STRATEGY = new StrategyEmail();
    private static final IStrategy MASK_ALL_STRATEGY = new StrategyMaskAll();

    private SensitiveMasker() {
    }

    /**
     * 对文本做统一脱敏，返回脱敏后的新字符串；入参为 null/空时原样返回。
     */
    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        // 顺序有讲究：先脱敏身份证，再脱敏银行卡，避免 18 位身份证被银行卡规则重复处理
        String masked = maskPattern(text, ID_CARD, ID_NO_STRATEGY);
        masked = maskPattern(masked, BANK_CARD, CARD_ID_STRATEGY);
        masked = maskPattern(masked, PHONE, PHONE_STRATEGY);
        masked = maskPattern(masked, EMAIL, EMAIL_STRATEGY);
        masked = maskPattern(masked, SK_KEY, MASK_ALL_STRATEGY);
        masked = maskKvSecrets(masked);
        return masked;
    }

    /** 正则定位命中片段，命中的整值交给 houbb 框架策略脱敏。 */
    private static String maskPattern(String text, Pattern pattern, IStrategy strategy) {
        Matcher m = pattern.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(desValue(strategy, m.group())));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 键值对秘钥：保留 key 名与分隔符，仅对 value 做全量脱敏。 */
    private static String maskKvSecrets(String text) {
        Matcher m = KV_SECRET.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String replacement = m.group(1) + m.group(2) + desValue(MASK_ALL_STRATEGY, m.group(3));
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 调用 houbb sensitive 框架策略对单个值脱敏；框架返回 null（如全隐藏）时兜底为 ****。 */
    private static String desValue(IStrategy strategy, String value) {
        Object masked = strategy.des(value, SensitiveContext.newInstance());
        return masked == null ? "****" : String.valueOf(masked);
    }
}
