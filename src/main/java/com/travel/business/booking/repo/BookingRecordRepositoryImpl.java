package com.travel.business.booking.repo;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.travel.business.booking.entity.BookingRecord;
import com.travel.business.booking.entity.BookingStatus;
import com.travel.business.booking.entity.BookingType;
import com.travel.business.booking.mapper.BookingRecordMapper;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/**
 * 预订记录数据库实现（基于 MyBatis-Plus）
 *
 * @author Hollis
 */
@Repository
public class BookingRecordRepositoryImpl implements BookingRecordRepository {

    @Autowired
    private BookingRecordMapper bookingRecordMapper;

    @Override
    public List<BookingRecord> findByUserId(String userId) {
        return bookingRecordMapper.selectList(
                new LambdaQueryWrapper<BookingRecord>()
                        .eq(BookingRecord::getUserId, userId)
                        .orderByDesc(BookingRecord::getBookedAt));
    }

    @Override
    public List<BookingRecord> findByUserIdAndBizType(String userId, BookingType bizType) {
        return bookingRecordMapper.selectList(
                new LambdaQueryWrapper<BookingRecord>()
                        .eq(BookingRecord::getUserId, userId)
                        .eq(BookingRecord::getBizType, bizType)
                        .orderByDesc(BookingRecord::getBookedAt));
    }

    @Override
    public List<BookingRecord> findByUserIdAndTravelOrderId(String userId, String travelOrderId) {
        return bookingRecordMapper.selectList(
                new LambdaQueryWrapper<BookingRecord>()
                        .eq(BookingRecord::getUserId, userId)
                        .eq(BookingRecord::getTravelOrderId, travelOrderId)
                        .orderByDesc(BookingRecord::getBookedAt));
    }

    @Override
    public Optional<BookingRecord> findByBookingId(String bookingId) {
        return Optional.ofNullable(bookingRecordMapper.selectOne(
                new LambdaQueryWrapper<BookingRecord>().eq(BookingRecord::getBookingId, bookingId)));
    }

    @Override
    public Optional<BookingRecord> findByExternalOrder(String platform, BookingType bizType, String externalOrderNo) {
        return Optional.ofNullable(bookingRecordMapper.selectOne(
                new LambdaQueryWrapper<BookingRecord>()
                        .eq(BookingRecord::getPlatform, platform)
                        .eq(BookingRecord::getBizType, bizType)
                        .eq(BookingRecord::getExternalOrderNo, externalOrderNo)));
    }

    @Override
    public BookingRecord save(BookingRecord record) {
        if (record.getBookingId() == null || record.getBookingId().isBlank()) {
            record.setBookingId("bk_" + System.currentTimeMillis());
        }
        BookingRecord existing = bookingRecordMapper.selectOne(
                new LambdaQueryWrapper<BookingRecord>().eq(BookingRecord::getBookingId, record.getBookingId()));
        if (existing == null) {
            bookingRecordMapper.insert(record);
        } else {
            record.setId(existing.getId());
            bookingRecordMapper.updateById(record);
        }
        return record;
    }

    @Override
    public boolean updateStatus(String bookingId, BookingStatus status, String externalStatus) {
        LambdaUpdateWrapper<BookingRecord> wrapper = new LambdaUpdateWrapper<BookingRecord>()
                .eq(BookingRecord::getBookingId, bookingId)
                .set(BookingRecord::getStatus, status);
        if (externalStatus != null) {
            wrapper.set(BookingRecord::getExternalStatus, externalStatus);
        }
        return bookingRecordMapper.update(wrapper) > 0;
    }
}
