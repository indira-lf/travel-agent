package com.gogo.travel.business.approval.repo;

import com.gogo.travel.business.approval.entity.ApprovalRecord;
import com.gogo.travel.business.approval.entity.ApprovalRecordStatus;

import java.util.List;
import java.util.Optional;

/**
 * @author Hollis
 */
public interface ApprovalRepository {

    Optional<ApprovalRecord> findByProcessInstanceId(String processInstanceId);

    List<ApprovalRecord> findByUserId(String userId);

    /** 查询全部审批单，按提交时间倒序 */
    List<ApprovalRecord> findAll();

    /** 按状态查询审批单，按提交时间倒序 */
    List<ApprovalRecord> findByStatus(ApprovalRecordStatus status);

    /** 按差旅单ID查询最新审批单 */
    Optional<ApprovalRecord> findLatestByOrderId(String orderId);

    ApprovalRecord save(ApprovalRecord record);
}
