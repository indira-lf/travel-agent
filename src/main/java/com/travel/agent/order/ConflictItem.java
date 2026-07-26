package com.travel.agent.order;

import com.alibaba.fastjson2.annotation.JSONField;

/**
 * 单条行程冲突明细。
 * <p>严重等级语义：</p>
 * <ul>
 *   <li>{@code HIGH}   — 物理上不可能：同时身处两地、出发当日无法赶到机场等。强烈建议用户调整。</li>
 *   <li>{@code MEDIUM} — 衔接紧张或路径断裂：行程间跨城交通时间不足。建议调整。</li>
 *   <li>{@code LOW}    — 仅作提示：同时间段相同城市重复提交等。允许直接继续。</li>
 * </ul>
 *
 * @author Hollis
 */
public class ConflictItem {

    public static final String SEVERITY_HIGH = "HIGH";
    public static final String SEVERITY_MEDIUM = "MEDIUM";
    public static final String SEVERITY_LOW = "LOW";

    /** 冲突类型枚举值 */
    public static final String TYPE_TIME_OVERLAP_SAME_CITY = "TIME_OVERLAP_SAME_CITY";
    public static final String TYPE_TIME_OVERLAP_DIFF_CITY = "TIME_OVERLAP_DIFF_CITY";
    public static final String TYPE_TRANSIT_TOO_TIGHT = "TRANSIT_TOO_TIGHT";
    public static final String TYPE_DISCONNECTED_ROUTE = "DISCONNECTED_ROUTE";

    /** 冲突类型 */
    @JSONField(name = "type")
    private String type;

    /** 严重等级：HIGH / MEDIUM / LOW */
    @JSONField(name = "severity")
    private String severity;

    /** 冲突的已有差旅单 ID */
    @JSONField(name = "order_id")
    private String orderId;

    /** 已有差旅单的简要描述（出发→到达 + 日期） */
    @JSONField(name = "order_summary")
    private String orderSummary;

    /** 人类可读的冲突原因 */
    @JSONField(name = "description")
    private String description;

    /** 调整建议（自动生成） */
    @JSONField(name = "suggestion")
    private String suggestion;

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }

    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }

    public String getOrderSummary() { return orderSummary; }
    public void setOrderSummary(String orderSummary) { this.orderSummary = orderSummary; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getSuggestion() { return suggestion; }
    public void setSuggestion(String suggestion) { this.suggestion = suggestion; }
}
