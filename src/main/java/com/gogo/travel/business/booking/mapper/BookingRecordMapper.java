package com.gogo.travel.business.booking.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gogo.travel.business.booking.entity.BookingRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * 预订记录 Mapper
 *
 * @author Hollis
 */
@Mapper
public interface BookingRecordMapper extends BaseMapper<BookingRecord> {
}
