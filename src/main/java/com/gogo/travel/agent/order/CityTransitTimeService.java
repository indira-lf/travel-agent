package com.gogo.travel.agent.order;

import com.gogo.travel.config.TravelPolicyProperties;
import jakarta.annotation.PostConstruct;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 城市间交通时长估算服务。todo 这个服务可以改用skill或者工具来实际查询城际交通用时。
 *
 * <p>用于行程冲突检测中判断"用户是否能在两个差旅单之间完成跨城交通"。
 * 数据来源优先级：</p>
 * <ol>
 *   <li>显式配置的 {@code cityPairMinutes}（覆盖默认推断）</li>
 *   <li>城市分层推断（一线/新一线/二线/其他，配置来自 {@link TravelPolicyProperties}）</li>
 *   <li>兜底：4 小时</li>
 * </ol>
 *
 * <p>单位：分钟。包含通勤（去车站/机场）+ 候车/候机 + 在途 + 离站全过程的最小时长。</p>
 *
 * @author Hollis
 */
@Service
public class CityTransitTimeService {

    private static final Logger logger = LoggerFactory.getLogger(CityTransitTimeService.class);

    /** 兜底时长：4 小时（覆盖大多数城际航班/高铁） */
    private static final int DEFAULT_MINUTES = 240;

    /** 同城视为 0 */
    private static final int SAME_CITY_MINUTES = 0;

    /** 含其他/未知城市：6 小时 */
    private static final int TIER_OTHER_MINUTES = 360;

    /** 含二线城市：5 小时 */
    private static final int TIER2_MINUTES = 300;

    /** 一线 / 新一线城市互达：4.5 小时 */
    private static final int TIER1_MINUTES = 270;

    @Autowired
    private TravelPolicyProperties policyProperties;
    private Map<String, Integer> cityPairMinutes;

    @PostConstruct
    public void init() {
        this.cityPairMinutes = policyProperties.getCityPairMinutes() == null
                ? Map.of() : policyProperties.getCityPairMinutes();
        if (logger.isDebugEnabled()) {
            logger.debug("[CityTransitTime] 显式 city-pair 配置 {} 对；tier 信息来自 TravelPolicyProperties",
                    this.cityPairMinutes.size());
        }
    }

    /**
     * 估算从 fromCity 到 toCity 的最短衔接时长（分钟）。
     * 任一端为 null/空时返回 {@link #DEFAULT_MINUTES}。
     */
    public int estimateMinutes(String fromCity, String toCity) {
        if (isBlank(fromCity) || isBlank(toCity)) {
            return DEFAULT_MINUTES;
        }
        if (normalize(fromCity).equals(normalize(toCity))) {
            return SAME_CITY_MINUTES;
        }
        String key = pairKey(fromCity, toCity);
        Integer explicit = cityPairMinutes.get(key);
        if (explicit != null) {
            return explicit;
        }
        return estimateByTier(fromCity, toCity);
    }

    /**
     * 城市分层推断时长。
     * <ul>
     *   <li>一线↔一线：4.5 小时</li>
     *   <li>含新一线：4.5 小时</li>
     *   <li>含二线：5 小时</li>
     *   <li>其他/未知：6 小时</li>
     * </ul>
     */
    private int estimateByTier(String fromCity, String toCity) {
        CityTier from = tierOf(fromCity);
        CityTier to = tierOf(toCity);
        int maxRank = Math.max(from.rank(), to.rank());
        if (maxRank == CityTier.OTHER.rank()) {
            return TIER_OTHER_MINUTES;
        }
        if (maxRank == CityTier.TIER2.rank()) {
            return TIER2_MINUTES;
        }
        return TIER1_MINUTES;
    }

    private CityTier tierOf(String city) {
        String n = normalize(city);
        if (contains(policyProperties.getTier1Cities(), n)) {
            return CityTier.TIER1;
        }
        if (contains(policyProperties.getNewTier1Cities(), n)) {
            return CityTier.NEW_TIER1;
        }
        if (contains(policyProperties.getTier2Cities(), n)) {
            return CityTier.TIER2;
        }
        return CityTier.OTHER;
    }

    private boolean contains(List<String> list, String normalizedCity) {
        if (list == null) {
            return false;
        }
        for (String s : list) {
            if (normalize(s).equals(normalizedCity)) {
                return true;
            }
        }
        return false;
    }

    /** 统一忽略大小写、去除首尾空白与“市”后缀 */
    private String normalize(String city) {
        if (city == null) {
            return "";
        }
        String s = city.trim();
        if (s.endsWith("市") && s.length() > 1) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /** 显式配置的 key 形如 "上海-北京"，顺序无关 */
    private String pairKey(String a, String b) {
        Set<String> set = new HashSet<>();
        set.add(normalize(a));
        set.add(normalize(b));
        return String.join("-", set);
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** 返回全量已加载城市分层快照，方便测试断言 */
    public Map<String, CityTier> snapshotTiers() {
        Map<String, CityTier> snap = new HashMap<>();
        for (String c : policyProperties.getTier1Cities()) {
            snap.put(normalize(c), CityTier.TIER1);
        }
        for (String c : policyProperties.getNewTier1Cities()) {
            snap.put(normalize(c), CityTier.NEW_TIER1);
        }
        for (String c : policyProperties.getTier2Cities()) {
            snap.put(normalize(c), CityTier.TIER2);
        }
        return snap;
    }

    public enum CityTier {
        /**
         * 一线城市
         */
        TIER1(0),
        /**
         * 新一线城市
         */
        NEW_TIER1(1),
        /**
         * 二线城市
         */
        TIER2(2),
        /**
         * 其他城市
         */
        OTHER(3);

        private final int rank;

        CityTier(int rank) {
            this.rank = rank;
        }

        public int rank() {
            return rank;
        }
    }
}
