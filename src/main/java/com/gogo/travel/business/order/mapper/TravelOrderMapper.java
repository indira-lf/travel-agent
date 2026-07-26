package com.gogo.travel.business.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gogo.travel.business.order.entity.TravelOrder;
import org.apache.ibatis.annotations.Mapper;

/**
 * 差旅单 Mapper
 *
 * @author Hollis
 */
@Mapper
public interface TravelOrderMapper extends BaseMapper<TravelOrder> {
}
