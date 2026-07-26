package com.gogo.travel.business.policy.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.gogo.travel.business.policy.entity.PolicyCheckResult;
import com.gogo.travel.business.policy.entity.TravelPolicy;
import com.gogo.travel.business.policy.entity.TravelPolicyRule;
import com.gogo.travel.business.policy.repo.TravelPolicyRuleRepository;
import com.gogo.travel.business.user.entity.UserProfile;
import com.gogo.travel.business.user.repo.UserProfileRepository;
import com.gogo.travel.config.TravelPolicyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * @author Hollis
 */
@Service
public class TravelPolicyService {

    private static final Logger logger = LoggerFactory.getLogger(TravelPolicyService.class);

    @Autowired
    private UserProfileRepository userProfileRepository;
    @Autowired
    private TravelPolicyProperties travelPolicyProperties;
    @Autowired
    private TravelPolicyRuleRepository travelPolicyRuleRepository;

    /**
     * 根据用户职级和目的城市等级获取差旅政策。
     */
    public TravelPolicy getPolicy(String userId, String destinationCity) {
        String level = resolveUserLevel(userId);
        String cityTier = resolveCityTier(destinationCity);
        logger.info("[PolicyService] userId={}, level={}, city={}, tier={}", userId, level, destinationCity, cityTier);
        return buildPolicy(level, cityTier);
    }

    /**
     * 校验订单摘要是否符合差旅政策。
     */
    public PolicyCheckResult checkCompliance(String orderSummaryJson, TravelPolicy policy) {
        PolicyCheckResult result = PolicyCheckResult.compliant();
        JSONObject order = JSON.parseObject(orderSummaryJson);
        if (order == null) {
            return result;
        }

        String type = order.getString("type");
        double amount = order.getDoubleValue("amount");

        if ("FLIGHT".equalsIgnoreCase(type)) {
            String flightClass = order.getString("flightClass");
            if (flightClass != null && !policy.getFlightClass().contains(flightClass)) {
                result.setCompliant(false);
                result.getViolations().add("机票舱位超出政策标准：允许 " + policy.getFlightClass());
            }
        } else if ("HOTEL".equalsIgnoreCase(type)) {
            if (amount > policy.getHotelLimit()) {
                result.setCompliant(false);
                result.getViolations().add("酒店金额超出政策上限：" + policy.getHotelLimit());
                result.getSuggestions().add("建议选择 " + policy.getHotelLimit() + " 元以下酒店");
            }
        } else if ("TRAIN".equalsIgnoreCase(type)) {
            String seatClass = order.getString("seatClass");
            if (seatClass != null && !seatClass.equalsIgnoreCase(policy.getTrainSeatClass())) {
                result.setCompliant(false);
                result.getViolations().add("火车座位超出政策标准：允许 " + policy.getTrainSeatClass());
            }
        }

        return result;
    }

    // -------------------------------------------------------------------------
    // 私有方法
    // -------------------------------------------------------------------------

    /**
     * 从用户档案中读取职级。
     * 若用户不存在或未配置职级，直接抛出异常。
     */
    private String resolveUserLevel(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("user_id 不能为空");
        }
        UserProfile profile =
                userProfileRepository.findByUserId(userId)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "用户档案不存在，无法获取差旅政策：userId=" + userId));
        String level = profile.getLevel();
        if (level == null || level.isBlank()) {
            throw new IllegalStateException(
                    "用户未配置职级，无法获取差旅政策：userId=" + userId);
        }
        return level;
    }

    /**
     * 查询城市分级。从 city-tier.yml 配置读取，未列出的城市默认返回"其他"。
     */
    private String resolveCityTier(String destinationCity) {
        if (destinationCity == null || destinationCity.isBlank()) {
            return "其他";
        }
        String city = destinationCity.trim();
        if (travelPolicyProperties.getTier1Cities().contains(city)) {
            return "一线";
        }
        if (travelPolicyProperties.getNewTier1Cities().contains(city)) {
            return "新一线";
        }
        if (travelPolicyProperties.getTier2Cities().contains(city)) {
            return "二线";
        }
        return "其他";
    }

    /**
     * 从数据库查询差旅政策规则并构建政策对象。
     * <p>政策规则表的城市等级仅区分：一线 / 新一线 / 其他（二线、三线及未配置城市均归为"其他"）。
     */
    private TravelPolicy buildPolicy(String level, String cityTier) {
        int levelNum = parseLevelNum(level);
        // 政策规则表只有 3 档城市等级
        String policyTier = ("一线".equals(cityTier) || "新一线".equals(cityTier)) ? cityTier : "其他";

        TravelPolicyRule rule = travelPolicyRuleRepository
                .findByLevelAndCityTier(levelNum, policyTier)
                .orElseThrow(() -> new IllegalStateException(
                        String.format("未找到匹配的差旅政策规则：level=%s(num=%d), cityTier=%s",
                                level, levelNum, policyTier)));

        TravelPolicy policy = new TravelPolicy();
        policy.setUserLevel(level);
        policy.setCityTier(cityTier);
        policy.setFlightClass(rule.getFlightClass());
        policy.setTrainSeatClass(rule.getTrainSeatClass());
        policy.setHotelLimit(rule.getHotelLimit());
        policy.setHotelStarLimit(rule.getHotelStarLimit());
        policy.setDailyMealLimit(rule.getDailyMealLimit());
        policy.setDailyTransportLimit(rule.getDailyTransportLimit());
        policy.setApprovalThreshold(rule.getApprovalThreshold());
        policy.setAdvanceBookingDays(rule.getAdvanceBookingDays());
        return policy;
    }

    /**
     * 解析职级中的数字部分（如 "P7" → 7）。
     * 无法解析时返回 1（按最低档处理，不使用默认值掩盖配置错误）。
     */
    private static int parseLevelNum(String level) {
        String digits = level.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            throw new IllegalArgumentException("职级格式无效，无法解析数字：" + level);
        }
        return Integer.parseInt(digits);
    }
}
