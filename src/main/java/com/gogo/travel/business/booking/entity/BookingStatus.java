package com.gogo.travel.business.booking.entity;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * 统一预订状态枚举（屏蔽不同平台的原始状态码差异）。
 * <p>各平台的原始状态请保存到 {@code externalStatus} 字段以便追溯。
 * <ul>
 *   <li>CREATED          - 已创建（下单成功，未支付）</li>
 *   <li>PENDING_PAYMENT  - 待支付</li>
 *   <li>PAID             - 已支付</li>
 *   <li>CONFIRMED        - 已确认（出票/确认入住等）</li>
 *   <li>COMPLETED        - 已完成（行程结束）</li>
 *   <li>CANCELLED        - 已取消</li>
 *   <li>REFUNDED         - 已退款</li>
 *   <li>FAILED           - 预订失败</li>
 * </ul>
 *
 * @author Hollis
 */
public enum BookingStatus {
    /**
     * 已创建
     */
    CREATED("CREATED", "已创建"),
    /**
     * 待支付
     */
    PENDING_PAYMENT("PENDING_PAYMENT", "待支付"),
    /**
     * 已支付
     */
    PAID("PAID", "已支付"),
    /**
     * 已确认
     */
    CONFIRMED("CONFIRMED", "已确认"),
    /**
     * 已完成
     */
    COMPLETED("COMPLETED", "已完成"),
    /**
     * 已取消
     */
    CANCELLED("CANCELLED", "已取消"),
    /**
     * 已退款
     */
    REFUNDED("REFUNDED", "已退款"),
    /**
     * 预订失败
     */
    FAILED("FAILED", "预订失败");

    /**
     * 存入数据库的值
     */
    @EnumValue
    private final String code;

    /**
     * 中文显示名
     */
    private final String label;

    BookingStatus(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    /**
     * 根据 code 安全解析，非法值返回 null
     */
    public static BookingStatus of(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (BookingStatus s : values()) {
            if (s.code.equalsIgnoreCase(code)) {
                return s;
            }
        }
        return null;
    }
}
