package com.gogo.travel.business.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gogo.travel.business.auth.entity.UserAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * @author Hollis
 */
@Mapper
public interface UserAccountMapper extends BaseMapper<UserAccount> {

    @Select("SELECT * FROM user_account WHERE username = #{username} LIMIT 1")
    UserAccount findByUsername(String username);

    @Select("SELECT * FROM user_account WHERE user_id = #{userId} LIMIT 1")
    UserAccount findByUserId(String userId);
}
