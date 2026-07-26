package com.travel.apikey.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.travel.apikey.entity.UserApiKeyEntry;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户 API Key Mapper
 *
 * @author Hollis
 */
@Mapper
public interface UserApiKeyMapper extends BaseMapper<UserApiKeyEntry> {
}
