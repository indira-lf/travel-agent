package com.travel.business.user.repo;

import com.travel.business.user.entity.UserProfile;

import java.util.Optional;

/**
 * @author Hollis
 */
public interface UserProfileRepository {

    Optional<UserProfile> findByUserId(String userId);

    UserProfile save(UserProfile profile);
}
