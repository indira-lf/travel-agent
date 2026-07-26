package com.travel.business.order.repo;

import com.travel.business.order.entity.TravelOrder;
import com.travel.business.order.entity.TravelOrderStatus;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * @author Hollis
 */
public interface TravelOrderRepository {

    List<TravelOrder> findByUserId(String userId);

    List<TravelOrder> findByUserIdAndStatus(String userId, TravelOrderStatus status);

    /**
     * 查询用户所有生效中的差旅单（DRAFT / SUBMITTED / APPROVED），
     * 可选地与给定日期范围 [fromDate, toDate] 有重叠。
     * <p>重叠判定：existing.departureDate <= toDate AND existing.returnDate >= fromDate。
     * <p>任何一端传 null 则不参与该端的比较。
     */
    List<TravelOrder> findActiveByUserIdAndDateRange(String userId, Collection<TravelOrderStatus> statuses,
                                                    String fromDate, String toDate);

    Optional<TravelOrder> findByOrderId(String orderId);

    TravelOrder save(TravelOrder order);

    /** 更新差旅单状态 */
    boolean updateStatus(String orderId, TravelOrderStatus status);
}
