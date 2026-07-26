package com.travel.business.order.repo;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.travel.business.order.entity.TravelOrder;
import com.travel.business.order.entity.TravelOrderStatus;
import com.travel.business.order.mapper.TravelOrderMapper;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/**
 * 差旅单数据库实现（基于 MyBatis-Plus）
 *
 * @author Hollis
 */
@Repository
public class TravelOrderRepositoryImpl implements TravelOrderRepository {

    @Autowired
    private TravelOrderMapper travelOrderMapper;

    @Override
    public List<TravelOrder> findByUserId(String userId) {
        return travelOrderMapper.selectList(
                new LambdaQueryWrapper<TravelOrder>().eq(TravelOrder::getUserId, userId));
    }

    @Override
    public List<TravelOrder> findByUserIdAndStatus(String userId, TravelOrderStatus status) {
        return travelOrderMapper.selectList(
                new LambdaQueryWrapper<TravelOrder>()
                        .eq(TravelOrder::getUserId, userId)
                        .eq(TravelOrder::getStatus, status));
    }

    @Override
    public List<TravelOrder> findActiveByUserIdAndDateRange(String userId,
                                                            java.util.Collection<TravelOrderStatus> statuses,
                                                            String fromDate, String toDate) {
        LambdaQueryWrapper<TravelOrder> wrapper = new LambdaQueryWrapper<TravelOrder>()
                .eq(TravelOrder::getUserId, userId);
        if (statuses != null && !statuses.isEmpty()) {
            wrapper.in(TravelOrder::getStatus, statuses);
        } else {
            // 默认查询生效中状态
            wrapper.in(TravelOrder::getStatus,
                    TravelOrderStatus.DRAFT, TravelOrderStatus.SUBMITTED, TravelOrderStatus.APPROVED);
        }
        // 日期重叠：existing.dep <= toDate AND existing.ret >= fromDate
        if (fromDate != null && !fromDate.isBlank()) {
            wrapper.ge(TravelOrder::getReturnDate, fromDate);
        }
        if (toDate != null && !toDate.isBlank()) {
            wrapper.le(TravelOrder::getDepartureDate, toDate);
        }
        wrapper.orderByAsc(TravelOrder::getDepartureDate);
        return travelOrderMapper.selectList(wrapper);
    }

    @Override
    public Optional<TravelOrder> findByOrderId(String orderId) {
        return Optional.ofNullable(travelOrderMapper.selectById(orderId));
    }

    @Override
    public TravelOrder save(TravelOrder order) {
        if (order.getOrderId() == null || order.getOrderId().isBlank()) {
            order.setOrderId("to_" + System.currentTimeMillis());
        }
        // 存在则更新，不存在则插入
        if (travelOrderMapper.selectById(order.getOrderId()) == null) {
            travelOrderMapper.insert(order);
        } else {
            travelOrderMapper.updateById(order);
        }
        return order;
    }

    @Override
    public boolean updateStatus(String orderId, TravelOrderStatus status) {
        int rows = travelOrderMapper.update(
                new LambdaUpdateWrapper<TravelOrder>()
                        .eq(TravelOrder::getOrderId, orderId)
                        .set(TravelOrder::getStatus, status));
        return rows > 0;
    }
}
