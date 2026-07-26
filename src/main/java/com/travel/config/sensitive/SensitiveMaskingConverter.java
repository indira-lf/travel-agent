package com.travel.config.sensitive;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * 统一日志脱敏转换器。
 *
 * <p>基于 logback {@link MessageConverter}，对日志消息中的敏感信息做<strong>精确、有边界</strong>的脱敏，
 * 具体脱敏规则统一委托给 {@link SensitiveMasker}，覆盖手机号、身份证号、银行卡号、邮箱、
 * {@code sk-} 形态 API Key 以及 password/secret/token 等键值对秘钥。</p>
 *
 * <p>相比 houbb 内置的 {@code SensitiveLogbackConverter} 全文扫描，这里改为「白名单式」精确匹配，
 * 避免把正常的中文业务日志（如「意图路由向量库启动完成」）误判为敏感信息而破坏日志可读性。
 * 通过 logback 的 {@code conversionRule} 注册后，所有日志输出会自动脱敏，对业务代码零侵入。</p>
 *
 * @author Hollis
 */
public class SensitiveMaskingConverter extends MessageConverter {

    @Override
    public String convert(ILoggingEvent event) {
        return SensitiveMasker.mask(super.convert(event));
    }
}
