package com.travel.business.user.repo;

import com.travel.business.user.entity.UserProfile;
import com.travel.business.user.mapper.UserProfileMapper;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/**
 * 用户档案数据库实现（基于 MyBatis-Plus）
 *
 * @author Hollis
 */
@Repository
public class UserProfileRepositoryImpl implements UserProfileRepository {

    @Autowired
    private UserProfileMapper userProfileMapper;

    @Override
    public Optional<UserProfile> findByUserId(String userId) {
        return Optional.ofNullable(userProfileMapper.selectById(userId));
    }

    @Override
    public UserProfile save(UserProfile profile) {
        if (userProfileMapper.selectById(profile.getUserId()) == null) {
            userProfileMapper.insert(profile);
        } else {
            userProfileMapper.updateById(profile);
        }
        return profile;
    }
}
