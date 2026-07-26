package com.travel.business.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.travel.business.user.entity.UserProfile;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户档案 Mapper
 *
 * @author Hollis
 */
@Mapper
public interface UserProfileMapper extends BaseMapper<UserProfile> {
}
