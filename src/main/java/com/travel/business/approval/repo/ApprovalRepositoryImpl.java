package com.travel.business.approval.repo;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.travel.business.approval.entity.ApprovalRecord;
import com.travel.business.approval.entity.ApprovalRecordStatus;
import com.travel.business.approval.mapper.ApprovalRecordMapper;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/**
 * 差旅审批记录数据库实现（基于 MyBatis-Plus）
 *
 * @author Hollis
 */
@Repository
public class ApprovalRepositoryImpl implements ApprovalRepository {

    @Autowired
    private ApprovalRecordMapper approvalRecordMapper;

    @Override
    public Optional<ApprovalRecord> findByProcessInstanceId(String processInstanceId) {
        return Optional.ofNullable(approvalRecordMapper.selectById(processInstanceId));
    }

    @Override
    public List<ApprovalRecord> findByUserId(String userId) {
        return approvalRecordMapper.selectList(
                new LambdaQueryWrapper<ApprovalRecord>().eq(ApprovalRecord::getUserId, userId));
    }

    @Override
    public List<ApprovalRecord> findAll() {
        return approvalRecordMapper.selectList(
                new LambdaQueryWrapper<ApprovalRecord>()
                        .orderByDesc(ApprovalRecord::getSubmitTime));
    }

    @Override
    public List<ApprovalRecord> findByStatus(ApprovalRecordStatus status) {
        return approvalRecordMapper.selectList(
                new LambdaQueryWrapper<ApprovalRecord>()
                        .eq(ApprovalRecord::getStatus, status)
                        .orderByDesc(ApprovalRecord::getSubmitTime));
    }

    @Override
    public Optional<ApprovalRecord> findLatestByOrderId(String orderId) {
        List<ApprovalRecord> list = approvalRecordMapper.selectList(
                new LambdaQueryWrapper<ApprovalRecord>()
                        .eq(ApprovalRecord::getOrderId, orderId)
                        .orderByDesc(ApprovalRecord::getSubmitTime)
                        .last("LIMIT 1"));
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    @Override
    public ApprovalRecord save(ApprovalRecord record) {
        // 存在则更新，不存在则插入
        if (approvalRecordMapper.selectById(record.getProcessInstanceId()) == null) {
            approvalRecordMapper.insert(record);
        } else {
            approvalRecordMapper.updateById(record);
        }
        return record;
    }
}
