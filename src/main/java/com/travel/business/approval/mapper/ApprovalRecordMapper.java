package com.travel.business.approval.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.travel.business.approval.entity.ApprovalRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * 差旅审批记录 Mapper
 *
 * @author Hollis
 */
@Mapper
public interface ApprovalRecordMapper extends BaseMapper<ApprovalRecord> {
}
