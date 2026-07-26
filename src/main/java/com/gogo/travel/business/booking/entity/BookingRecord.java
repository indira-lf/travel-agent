package com.gogo.travel.business.booking.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.gogo.travel.config.InstantTypeHandler;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Agent 预订记录，对应 {@code booking_record} 表。
 * <p>统一存储用户通过 Agent 预订的机票 / 酒店 / 火车票等，兼容不同预订平台：
 * <ul>
 *   <li>{@link #bizType} 区分业务类型，{@link #platform} 区分预订平台；</li>
 *   <li>{@link #externalOrderNo}/{@link #externalSubNo}/{@link #externalRefNo} 记录外部平台单号；</li>
 *   <li>{@link #status} 为统一状态，{@link #externalStatus} 保留平台原始状态码；</li>
 *   <li>{@link #detail} 存放平台差异化明细的 JSON 字符串。</li>
 * </ul>
 *
 * @author Hollis
 */
@TableName(value = "booking_record", autoResultMap = true)
public class BookingRecord {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 内部预订单号（系统生成，全局唯一） */
    private String bookingId;

    private String userId;

    /** 关联会话ID */
    private String conversationId;

    /** 关联差旅单ID（travel_order.order_id），可为空 */
    private String travelOrderId;

    /** 预订业务类型 */
    private BookingType bizType;

    /** 预订平台：tuniu / rolling-go-hotel / flight-manager 等 */
    private String platform;

    /** 外部平台主订单号 */
    private String externalOrderNo;


    /** 统一预订状态 */
    private BookingStatus status;

    /** 外部平台原始状态码/文案 */
    private String externalStatus;

    /** 支付状态：UNPAID/PAID/REFUNDED */
    private String paymentStatus;

    /** 预订标题（如“北京→上海 MU5101”或酒店名） */
    private String title;

    /** 订单总金额（元） */
    private BigDecimal totalAmount;

    /** 币种 */
    private String currency;

    private String contactName;

    private String contactPhone;

    /** 服务开始时间（出发/入住） */
    @TableField(typeHandler = InstantTypeHandler.class)
    private Instant startTime;

    /** 服务结束时间（到达/离店） */
    @TableField(typeHandler = InstantTypeHandler.class)
    private Instant endTime;

    /** 平台原始/明细信息 JSON 字符串（航班号、房型、乘客、行程等） */
    private String detail;

    private String remark;

    /** 下单时间 */
    @TableField(typeHandler = InstantTypeHandler.class)
    private Instant bookedAt;

    @TableField(fill = FieldFill.INSERT, typeHandler = InstantTypeHandler.class)
    private Instant createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE, typeHandler = InstantTypeHandler.class)
    private Instant updatedAt;

    @TableLogic
    @TableField(value = "deleted")
    private Integer deleted;

    public BookingRecord() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getBookingId() {
        return bookingId;
    }

    public void setBookingId(String bookingId) {
        this.bookingId = bookingId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getTravelOrderId() {
        return travelOrderId;
    }

    public void setTravelOrderId(String travelOrderId) {
        this.travelOrderId = travelOrderId;
    }

    public BookingType getBizType() {
        return bizType;
    }

    public void setBizType(BookingType bizType) {
        this.bizType = bizType;
    }

    public String getPlatform() {
        return platform;
    }

    public void setPlatform(String platform) {
        this.platform = platform;
    }

    public String getExternalOrderNo() {
        return externalOrderNo;
    }

    public void setExternalOrderNo(String externalOrderNo) {
        this.externalOrderNo = externalOrderNo;
    }



    public BookingStatus getStatus() {
        return status;
    }

    public void setStatus(BookingStatus status) {
        this.status = status;
    }

    public String getExternalStatus() {
        return externalStatus;
    }

    public void setExternalStatus(String externalStatus) {
        this.externalStatus = externalStatus;
    }

    public String getPaymentStatus() {
        return paymentStatus;
    }

    public void setPaymentStatus(String paymentStatus) {
        this.paymentStatus = paymentStatus;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getContactName() {
        return contactName;
    }

    public void setContactName(String contactName) {
        this.contactName = contactName;
    }

    public String getContactPhone() {
        return contactPhone;
    }

    public void setContactPhone(String contactPhone) {
        this.contactPhone = contactPhone;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public void setStartTime(Instant startTime) {
        this.startTime = startTime;
    }

    public Instant getEndTime() {
        return endTime;
    }

    public void setEndTime(Instant endTime) {
        this.endTime = endTime;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public Instant getBookedAt() {
        return bookedAt;
    }

    public void setBookedAt(Instant bookedAt) {
        this.bookedAt = bookedAt;
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

    public Integer getDeleted() {
        return deleted;
    }

    public void setDeleted(Integer deleted) {
        this.deleted = deleted;
    }
}
