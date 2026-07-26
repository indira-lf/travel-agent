package com.gogo.travel.business.order.entity;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * 差旅单状态枚举
 * <ul>
 *   <li>DRAFT      - 草稿（已创建，未提交）</li>
 *   <li>SUBMITTED  - 已提交（审批中）</li>
 *   <li>APPROVED   - 已审批通过</li>
 *   <li>REJECTED   - 已拒绝</li>
 *   <li>COMPLETED  - 已完成</li>
 *   <li>CANCELLED  - 已取消</li>
 * </ul>
 *
 * @author Hollis
 */
public enum TravelOrderStatus {

    /**
     * 草稿
     */
    DRAFT("DRAFT", "草稿"),
    /**
     * 审批中
     */
    SUBMITTED("SUBMITTED", "审批中"),
    /**
     * 已通过
     */
    APPROVED("APPROVED", "已通过"),
    /**
     * 已拒绝
     */
    REJECTED("REJECTED", "已拒绝"),
    /**
     * 已完成
     */
    COMPLETED("COMPLETED", "已完成"),
    /**
     * 已取消
     */
    CANCELLED("CANCELLED", "已取消");

    /**
     * 存入数据库的值（与历史字符串兼容）
     */
    @EnumValue
    private final String code;

    /**
     * 中文显示名
     */
    private final String label;

    TravelOrderStatus(String code, String label) {
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
    public static TravelOrderStatus of(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (TravelOrderStatus s : values()) {
            if (s.code.equalsIgnoreCase(code)) {
                return s;
            }
        }
        return null;
    }
}
