package com.gogo.travel.business.policy.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 差旅政策规则（按职级区间 × 城市等级配置）。
 * <p>城市等级取值：一线 / 新一线 / 其他（涵盖二线、三线及未配置城市）。
 * <p>职级区间用 [levelMin, levelMax] 表示数字范围，如 P6 → levelMin=6, levelMax=6；
 * P8+ → levelMin=8, levelMax=99。
 *
 * @author Hollis
 */
@TableName("travel_policy_rule")
public class TravelPolicyRule {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 职级区间下限（含） */
    private Integer levelMin;

    /** 职级区间上限（含），99 表示无上限 */
    private Integer levelMax;

    /** 城市等级：一线 / 新一线 / 其他 */
    private String cityTier;

    /** 允许的最高机票舱位，如 经济舱 / 经济舱/商务舱 / 商务舱 */
    private String flightClass;

    /** 允许的最高高铁座位，如 二等座 / 一等座 */
    private String trainSeatClass;

    /** 酒店每晚上限（元） */
    private Double hotelLimit;

    /** 酒店星级上限 */
    private Integer hotelStarLimit;

    /** 餐补每日上限（元） */
    private Double dailyMealLimit;

    /** 交通补贴每日上限（元） */
    private Double dailyTransportLimit;

    /** 审批金额阈值（超过此金额需走审批流程） */
    private Double approvalThreshold;

    /** 提前预订最小天数 */
    private Integer advanceBookingDays;

    public TravelPolicyRule() {
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Integer getLevelMin() { return levelMin; }
    public void setLevelMin(Integer levelMin) { this.levelMin = levelMin; }

    public Integer getLevelMax() { return levelMax; }
    public void setLevelMax(Integer levelMax) { this.levelMax = levelMax; }

    public String getCityTier() { return cityTier; }
    public void setCityTier(String cityTier) { this.cityTier = cityTier; }

    public String getFlightClass() { return flightClass; }
    public void setFlightClass(String flightClass) { this.flightClass = flightClass; }

    public String getTrainSeatClass() { return trainSeatClass; }
    public void setTrainSeatClass(String trainSeatClass) { this.trainSeatClass = trainSeatClass; }

    public Double getHotelLimit() { return hotelLimit; }
    public void setHotelLimit(Double hotelLimit) { this.hotelLimit = hotelLimit; }

    public Integer getHotelStarLimit() { return hotelStarLimit; }
    public void setHotelStarLimit(Integer hotelStarLimit) { this.hotelStarLimit = hotelStarLimit; }

    public Double getDailyMealLimit() { return dailyMealLimit; }
    public void setDailyMealLimit(Double dailyMealLimit) { this.dailyMealLimit = dailyMealLimit; }

    public Double getDailyTransportLimit() { return dailyTransportLimit; }
    public void setDailyTransportLimit(Double dailyTransportLimit) { this.dailyTransportLimit = dailyTransportLimit; }

    public Double getApprovalThreshold() { return approvalThreshold; }
    public void setApprovalThreshold(Double approvalThreshold) { this.approvalThreshold = approvalThreshold; }

    public Integer getAdvanceBookingDays() { return advanceBookingDays; }
    public void setAdvanceBookingDays(Integer advanceBookingDays) { this.advanceBookingDays = advanceBookingDays; }
}
