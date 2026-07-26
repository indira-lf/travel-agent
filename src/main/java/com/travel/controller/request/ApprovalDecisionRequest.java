package com.travel.controller.request;

/**
 * 后台管理员审批决策请求。
 *
 * @author Hollis
 */
public class ApprovalDecisionRequest {

    /** 决策：agree=通过，refuse=拒绝 */
    private String decision;
    /** 审批备注（可选） */
    private String remark;

    public String getDecision() {
        return decision;
    }

    public void setDecision(String decision) {
        this.decision = decision;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
