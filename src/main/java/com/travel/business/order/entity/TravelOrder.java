package com.travel.business.order.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.travel.config.InstantTypeHandler;
import java.time.Instant;

/**
 * 差旅单（出差申请单）
 *
 * @author Hollis
 */
@TableName(value = "travel_order", autoResultMap = true)
public class TravelOrder {

    @TableId(value = "order_id", type = IdType.INPUT)
    private String orderId;
    private String userId;
    private String destination;
    private String departureCity;
    private String departureDate;
    private String returnDate;
    private String purpose;
    private TravelOrderStatus status;
    /** 关联审批单ID，提交审批后写入 */
    private String approvalId;
    /** 创建时间（自动填充） */
    @TableField(fill = FieldFill.INSERT, typeHandler = InstantTypeHandler.class)
    private Instant createdAt;
    /** 最后修改时间（自动填充） */
    @TableField(fill = FieldFill.INSERT_UPDATE, typeHandler = InstantTypeHandler.class)
    private Instant updatedAt;

    public TravelOrder() {
    }

    public TravelOrder(String orderId, String userId, String destination, String departureCity,
                       String departureDate, String returnDate, String purpose, TravelOrderStatus status) {
        this.orderId = orderId;
        this.userId = userId;
        this.destination = destination;
        this.departureCity = departureCity;
        this.departureDate = departureDate;
        this.returnDate = returnDate;
        this.purpose = purpose;
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getApprovalId() {
        return approvalId;
    }

    public void setApprovalId(String approvalId) {
        this.approvalId = approvalId;
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getDestination() {
        return destination;
    }

    public void setDestination(String destination) {
        this.destination = destination;
    }

    public String getDepartureCity() {
        return departureCity;
    }

    public void setDepartureCity(String departureCity) {
        this.departureCity = departureCity;
    }

    public String getDepartureDate() {
        return departureDate;
    }

    public void setDepartureDate(String departureDate) {
        this.departureDate = departureDate;
    }

    public String getReturnDate() {
        return returnDate;
    }

    public void setReturnDate(String returnDate) {
        this.returnDate = returnDate;
    }

    public String getPurpose() {
        return purpose;
    }

    public void setPurpose(String purpose) {
        this.purpose = purpose;
    }

    public TravelOrderStatus getStatus() {
        return status;
    }

    public void setStatus(TravelOrderStatus status) {
        this.status = status;
    }
}
