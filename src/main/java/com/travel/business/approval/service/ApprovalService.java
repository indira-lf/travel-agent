package com.travel.business.approval.service;

import com.travel.business.approval.entity.ApprovalRecord;
import com.travel.business.approval.entity.ApprovalRecordStatus;
import com.travel.business.approval.repo.ApprovalRepository;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * @author Hollis
 */
@Service
public class ApprovalService {

    private static final Logger logger = LoggerFactory.getLogger(ApprovalService.class);

    @Autowired
    private ApprovalRepository approvalRepository;

    public ApprovalRecord submit(String userId, String title, String approvalForm) {
        return submit(userId, title, approvalForm, null);
    }

    public ApprovalRecord submit(String userId, String title, String approvalForm, String orderId) {
        String processInstanceId = "pi_" + System.currentTimeMillis();
        Instant now = Instant.now();
        ApprovalRecord record = new ApprovalRecord(
                processInstanceId,
                userId,
                title,
                ApprovalRecordStatus.PENDING,
                approvalForm,
                null,
                now,
                now);
        record.setOrderId(orderId);
        approvalRepository.save(record);
        logger.info("[ApprovalService] 提交审批 userId={}, processInstanceId={}, orderId={}",
                userId, processInstanceId, orderId);
        return record;
    }

    public Optional<ApprovalRecord> findByProcessInstanceId(String processInstanceId) {
        return approvalRepository.findByProcessInstanceId(processInstanceId);
    }

    /** 查询审批单列表，status 为 null 时返回全部，均按提交时间倒序 */
    public List<ApprovalRecord> list(ApprovalRecordStatus status) {
        return status == null ? approvalRepository.findAll() : approvalRepository.findByStatus(status);
    }

    /**
     * 管理员对审批单做出决策。
     *
     * @param processInstanceId 审批单ID
     * @param agree             true=通过，false=拒绝
     * @param remark            审批备注（可空）
     * @return 更新后的审批单；审批单不存在或非待审批状态时返回 empty
     */
    public Optional<ApprovalRecord> decide(String processInstanceId, boolean agree, String remark) {
        Optional<ApprovalRecord> opt = approvalRepository.findByProcessInstanceId(processInstanceId);
        if (opt.isEmpty()) {
            return Optional.empty();
        }
        ApprovalRecord record = opt.get();
        if (ApprovalRecordStatus.PENDING != record.getStatus()) {
            logger.warn("[ApprovalService] 审批单非待审批状态，忽略决策 processInstanceId={}, status={}",
                    processInstanceId, record.getStatus());
            return Optional.empty();
        }
        record.setStatus(agree ? ApprovalRecordStatus.APPROVED : ApprovalRecordStatus.REJECTED);
        if (remark != null && !remark.isBlank()) {
            record.setRemark(remark);
        }
        record.setUpdateTime(Instant.now());
        approvalRepository.save(record);
        logger.info("[ApprovalService] 管理员审批 processInstanceId={}, status={}",
                processInstanceId, record.getStatus());
        return Optional.of(record);
    }

    public Optional<ApprovalRecord> findLatestByUserId(String userId) {
        List<ApprovalRecord> records = approvalRepository.findByUserId(userId);
        if (records.isEmpty()) {
            return Optional.empty();
        }
        return records.stream()
                .max(Comparator.comparing(ApprovalRecord::getSubmitTime));
    }

    public void updateStatus(String processInstanceId, String result) {
        approvalRepository.findByProcessInstanceId(processInstanceId).ifPresent(record -> {
            ApprovalRecordStatus status = "agree".equalsIgnoreCase(result)
                    ? ApprovalRecordStatus.APPROVED : ApprovalRecordStatus.REJECTED;
            record.setStatus(status);
            record.setUpdateTime(Instant.now());
            approvalRepository.save(record);
            logger.info("[ApprovalService] 更新审批状态 processInstanceId={}, status={}", processInstanceId, status);
        });
    }

    public boolean cancel(String processInstanceId) {
        Optional<ApprovalRecord> opt = approvalRepository.findByProcessInstanceId(processInstanceId);
        if (opt.isEmpty()) {
            return false;
        }
        ApprovalRecord record = opt.get();
        if (ApprovalRecordStatus.CANCELLED == record.getStatus()) {
            return true;
        }
        record.setStatus(ApprovalRecordStatus.CANCELLED);
        record.setUpdateTime(Instant.now());
        approvalRepository.save(record);
        logger.info("[ApprovalService] 取消审批 processInstanceId={}", processInstanceId);
        return true;
    }

    public Optional<ApprovalRecord> findLatestByOrderId(String orderId) {
        return approvalRepository.findLatestByOrderId(orderId);
    }
}