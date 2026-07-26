package com.gogo.travel.business.policy.repo;

import com.gogo.travel.business.policy.entity.TravelPolicyRule;
import com.gogo.travel.business.policy.mapper.TravelPolicyRuleMapper;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/**
 * 差旅政策规则数据库实现（基于 MyBatis-Plus）
 *
 * @author Hollis
 */
@Repository
public class TravelPolicyRuleRepositoryImpl implements TravelPolicyRuleRepository {

    @Autowired
    private TravelPolicyRuleMapper travelPolicyRuleMapper;

    @Override
    public Optional<TravelPolicyRule> findByLevelAndCityTier(int levelNum, String cityTier) {
        return Optional.ofNullable(
                travelPolicyRuleMapper.findByLevelAndTier(levelNum, cityTier));
    }
}
