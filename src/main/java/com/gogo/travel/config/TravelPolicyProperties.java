package com.gogo.travel.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 差旅政策相关配置属性，绑定 city-tier.yml 中 travel.policy.* 配置项。
 * <p>YAML key 必须为 ASCII，中文城市名放在 List 值中以避免 Spring 松散绑定对中文 key 的兼容问题。
 *
 * @author Hollis
 */
@Component
@ConfigurationProperties(prefix = "travel.policy")
public class TravelPolicyProperties {

    /** 一线城市列表 */
    private List<String> tier1Cities = new ArrayList<>();

    /** 新一线城市列表 */
    private List<String> newTier1Cities = new ArrayList<>();

    /** 二线城市列表 */
    private List<String> tier2Cities = new ArrayList<>();

    /**
     * 城市间交通时长显式配置（分钟），key 形如 "上海-北京"，顺序无关。
     * 用于覆盖默认的城市分层推断。
     */
    private Map<String, Integer> cityPairMinutes = new HashMap<>();

    public List<String> getTier1Cities() { return tier1Cities; }
    public void setTier1Cities(List<String> tier1Cities) { this.tier1Cities = tier1Cities; }

    public List<String> getNewTier1Cities() { return newTier1Cities; }
    public void setNewTier1Cities(List<String> newTier1Cities) { this.newTier1Cities = newTier1Cities; }

    public List<String> getTier2Cities() { return tier2Cities; }
    public void setTier2Cities(List<String> tier2Cities) { this.tier2Cities = tier2Cities; }

    public Map<String, Integer> getCityPairMinutes() { return cityPairMinutes; }
    public void setCityPairMinutes(Map<String, Integer> cityPairMinutes) { this.cityPairMinutes = cityPairMinutes; }
}
