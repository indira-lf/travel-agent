package com.gogo.travel.business.policy.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gogo.travel.business.policy.entity.TravelPolicyRule;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 差旅政策规则 Mapper
 *
 * @author Hollis
 */
@Mapper
public interface TravelPolicyRuleMapper extends BaseMapper<TravelPolicyRule> {

    /**
     * 按职级数字区间 + 城市等级查询，返回第一条匹配规则。
     */
    @Select("SELECT * FROM travel_policy_rule "
            + "WHERE level_min <= #{levelNum} AND level_max >= #{levelNum} "
            + "  AND city_tier = #{cityTier} "
            + "LIMIT 1")
    TravelPolicyRule findByLevelAndTier(@Param("levelNum") int levelNum,
                                        @Param("cityTier") String cityTier);
}
