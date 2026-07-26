package com.gogo.travel.business.approval.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.gogo.travel.config.InstantTypeHandler;
import java.time.Instant;

/**
 * 差旅审批记录
 *
 * @author Hollis
 */
@TableName(value = "approval_record", autoResultMap = true)
public class ApprovalRecord {

    @TableId(value = "process_instance_id", type = IdType.INPUT)
    private String processInstanceId;
    private String userId;
    private String title;
    private ApprovalRecordStatus status;
    private String approvalForm;
    private String remark;
    @TableField(typeHandler = InstantTypeHandler.class)
    private Instant submitTime;
    @TableField(typeHandler = InstantTypeHandler.class)
    private Instant updateTime;
    /** 关联的差旅单ID */
    private String orderId;

    public ApprovalRecord() {
    }

    public ApprovalRecord(String processInstanceId, String userId, String title, ApprovalRecordStatus status,
                          String approvalForm, String remark, Instant submitTime, Instant updateTime) {
        this.processInstanceId = processInstanceId;
        this.userId = userId;
        this.title = title;
        this.status = status;
        this.approvalForm = approvalForm;
        this.remark = remark;
        this.submitTime = submitTime;
        this.updateTime = updateTime;
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public String getProcessInstanceId() {
        return processInstanceId;
    }

    public void setProcessInstanceId(String processInstanceId) {
        this.processInstanceId = processInstanceId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public ApprovalRecordStatus getStatus() {
        return status;
    }

    public void setStatus(ApprovalRecordStatus status) {
        this.status = status;
    }

    public String getApprovalForm() {
        return approvalForm;
    }

    public void setApprovalForm(String approvalForm) {
        this.approvalForm = approvalForm;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public Instant getSubmitTime() {
        return submitTime;
    }

    public void setSubmitTime(Instant submitTime) {
        this.submitTime = submitTime;
    }

    public Instant getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(Instant updateTime) {
        this.updateTime = updateTime;
    }
}
