package com.gogo.travel.business.policy.repo;

import com.gogo.travel.business.policy.entity.TravelPolicyRule;

import java.util.Optional;

/**
 * @author Hollis
 */
public interface TravelPolicyRuleRepository {

    /**
     * 根据职级数字和城市等级查询匹配的政策规则。
     * 城市等级取值：一线 / 新一线 / 其他
     */
    Optional<TravelPolicyRule> findByLevelAndCityTier(int levelNum, String cityTier);
}
