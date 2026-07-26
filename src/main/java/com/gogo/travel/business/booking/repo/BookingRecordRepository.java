package com.gogo.travel.business.booking.repo;

import com.gogo.travel.business.booking.entity.BookingRecord;
import com.gogo.travel.business.booking.entity.BookingStatus;
import com.gogo.travel.business.booking.entity.BookingType;
import java.util.List;
import java.util.Optional;

/**
 * 预订记录仓储接口
 *
 * @author Hollis
 */
public interface BookingRecordRepository {

    /** 查询用户的所有预订记录（按下单时间倒序） */
    List<BookingRecord> findByUserId(String userId);

    /** 按用户 + 业务类型查询 */
    List<BookingRecord> findByUserIdAndBizType(String userId, BookingType bizType);

    /** 按用户 + 差旅单ID查询（按下单时间倒序） */
    List<BookingRecord> findByUserIdAndTravelOrderId(String userId, String travelOrderId);

    /** 按内部预订单号查询 */
    Optional<BookingRecord> findByBookingId(String bookingId);

    /** 按平台 + 业务类型 + 外部主订单号查询（用于去重/回查） */
    Optional<BookingRecord> findByExternalOrder(String platform, BookingType bizType, String externalOrderNo);

    /**
     * 保存预订记录：存在则更新，不存在则插入。
     * <p>{@code bookingId} 为空时自动生成。
     */
    BookingRecord save(BookingRecord record);

    /** 更新预订状态（可同步写入外部平台原始状态，传 null 则不更新该字段） */
    boolean updateStatus(String bookingId, BookingStatus status, String externalStatus);
}
