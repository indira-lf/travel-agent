package com.travel.agent.tools;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.travel.agent.context.AgentSessionContext;
import com.travel.utils.JsonUtil;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.IntStream;

/**
 * 往返行程规划工具（去程 + 住宿 + 返程）的客观计算引擎。
 *
 * <p>设计原则：
 * <ul>
 *   <li>本工具只做"客观数学"：时间差、价格求和、min-max 归一化、policy 硬性数值比较、组合过滤、排序。</li>
 *   <li>本工具绝不做任何"偏好判断"。偏好分完全由 LLM 事先产出（scores 入参），
 *       工具只负责按 (去+住+返)/3 合成。</li>
 * </ul>
 *
 * <p>输入 {@code input}（原始规划输入）+ {@code scores}（LLM 偏好分）→ 内存里切分去/返程、
 * 做去×住×返笛卡尔积、过滤非法组合、算时间/价格并归一化、做 policy 软约束检查、合成偏好分、
 * 算 overall，选出 4 个代表方案（时间最短/价格最低/最符合偏好/综合最佳，命中多类则合并 tags），
 * 写出 result.json 并返回摘要（含结果文件绝对路径）。
 *
 * @author Hollis
 */
@Component
public class ItineraryPlannerTool {

    private static final Logger logger = LoggerFactory.getLogger(ItineraryPlannerTool.class);

    // ============================== 常量 ==============================

    /** 政策评分基础分 */
    private static final double POLICY_BASE_SCORE = 100.0;
    /** 每个 violation 扣减分值 */
    private static final double POLICY_VIOLATION_PENALTY = 40.0;
    /** 每个 warning 扣减分值 */
    private static final double POLICY_WARNING_PENALTY = 15.0;

    /** 默认维度权重：时间 */
    private static final double DEFAULT_WEIGHT_TIME = 0.3;
    /** 默认维度权重：价格 */
    private static final double DEFAULT_WEIGHT_PRICE = 0.2;
    /** 默认维度权重：偏好 */
    private static final double DEFAULT_WEIGHT_PREFERENCE = 0.5;

    /** 代表方案标签（有序） */
    private static final List<String> TAG_ORDER = List.of("综合最佳", "时间最短", "价格最低", "最符合偏好");

    /** legView 需要拷贝的字段 */
    private static final List<String> LEG_FIELDS = List.of(
            "id", "type", "carrier", "code", "departure_time", "arrival_time",
            "origin", "destination", "price", "cabin_class", "is_direct", "refund_policy");

    /** hotelView 需要拷贝的字段 */
    private static final List<String> HOTEL_FIELDS = List.of(
            "id", "name", "brand", "star_rating", "room_type", "price_per_night",
            "nights", "distance_to_dest_km", "breakfast_included", "cancel_policy");

    /** 舱位等价映射（统一转小写后比较） */
    private static final Map<String, String> CABIN_ALIASES = Map.of(
            "economy", "economy", "经济舱", "economy",
            "business", "business", "商务舱", "business",
            "first", "first", "头等舱", "first");

    private static final DateTimeFormatter[] DT_FORMATS = {
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
    };

    private final ItineraryPlanStore itineraryPlanStore;

    public ItineraryPlannerTool(ItineraryPlanStore itineraryPlanStore) {
        this.itineraryPlanStore = itineraryPlanStore;
    }

    // ============================== 内部数据结构（record） ==============================

    /** 政策检查结果 */
    private record PolicyResult(JSONArray violations, JSONArray warnings, double score) {}

    /** 单条候选偏好评分 */
    private record ScoreEntry(double score, JSONArray basis) {
        static final ScoreEntry EMPTY = new ScoreEntry(0, new JSONArray());
    }

    /** 中间计算上下文（构建阶段可变，构建完成后只读） */
    private static final class CalcContext {
        String error;
        int comboCount;
        JSONObject userRequest;
        String preferences;
        JSONObject weights;
        List<JSONObject> combos = new ArrayList<>();
        Map<String, JSONObject> legTransport = new LinkedHashMap<>();
        Map<String, JSONObject> legHotels = new LinkedHashMap<>();
    }

    // ============================== @Tool 入口 ==============================

    @Tool(
            name = "plan_itinerary",
            description = "往返行程规划客观计算引擎：输入候选交通/酒店 + 你（LLM）预先产出的偏好分，" +
                          "在内存里做去×住×返组合、过滤非法组合、算总价/总耗时并归一化、做 policy 软约束检查、" +
                          "按 (去程分+酒店分+返程分)/3 合成偏好分、按三维权重算综合分，选出 4 类代表方案" +
                          "（时间最短/价格最低/最符合偏好/综合最佳，命中多类合并 tags），将结果存入 Redis（按当前用户隔离，无需也无法指定存储位置）并返回摘要。" +
                          "本工具只做客观数学，绝不解读偏好文本；偏好判断必须由你在 scores 参数里给出。"
    )
    public String planItinerary(
            @ToolParam(name = "origin", description = "出发地城市，如'上海'")
            String origin,
            @ToolParam(name = "destination", description = "目的地城市，如'杭州'")
            String destination,
            @ToolParam(name = "departure_date", description = "去程日期，格式 YYYY-MM-DD")
            String departureDate,
            @ToolParam(name = "return_date", description = "返程日期，格式 YYYY-MM-DD")
            String returnDate,

            @ToolParam(name = "preferences",
                    description = "描述用户偏好的自由文本（来自 retrieve_from_memory），无则留空。仅供打分参考，本工具不解读。",
                    required = false)
            String preferences,

            @ToolParam(name = "candidates",
                    description = "候选交通与酒店 JSON：{ transport_options:[{id,type(flight|train),direction(outbound|return)," +
                                  "departure_time,arrival_time,origin,destination,price,cabin_class,is_direct,refund_policy}], " +
                                  "hotel_options:[{id,name,brand,star_rating,room_type,price_per_night,nights,distance_to_dest_km," +
                                  "breakfast_included,cancel_policy}] }。去程与返程交通都放进 transport_options，用 direction 区分。")
            String candidates,

            @ToolParam(name = "scores",
                    description = "LLM事先对每个候选打的偏好分 JSON。结构固定：" +
                                  "{ transport_scores:{ \"T1\":{score:0-100, basis:[中文理由]}, ... }, " +
                                  "hotel_scores:{ \"H1\":{score:0-100, basis:[中文理由]}, ... } }。" +
                                  "key 必须与 candidates 里交通/酒店的 id 完全一致，未打分的 id 按 0 分处理。")
            String scores,

            @ToolParam(name = "policy",
                    description = "差旅政策 JSON（来自 query_travel_policy 透传）：{hotel_limit_per_night,transport_class,flight_class,total_budget_limit}，无则留空。",
                    required = false)
            String policy,

            AgentSessionContext sessionCtx) {

        String userId = sessionCtx != null ? sessionCtx.getUserId() : null;
        logger.info("[TOOL][plan_itinerary] 开始规划，结果存入 Redis Key: {}", ItineraryPlanStore.keyOf(userId));

        JSONObject data;
        JSONObject scoreObj;
        try {
            data = buildInputData(origin, destination, departureDate, returnDate, preferences, candidates, policy);
            scoreObj = parseJsonOrEmpty(scores);
        } catch (Exception e) {
            logger.error("[TOOL][plan_itinerary] 入参 JSON 解析失败: {}", e.getMessage());
            return errorResult("candidates / scores / policy 不是合法 JSON：" + e.getMessage());
        }

        try {
            CalcContext ctx = buildCalcContext(data);
            if (ctx.error != null) {
                logger.warn("[TOOL][plan_itinerary] 规划失败: {}", ctx.error);
                return errorResult(ctx.error);
            }
            JSONObject result = rankAndBuildResult(ctx, scoreObj);
            String resultJson = JSON.toJSONString(result, com.alibaba.fastjson2.JSONWriter.Feature.PrettyFormat);
            itineraryPlanStore.save(userId, resultJson);

            return buildSummary(result, ctx, userId);
        } catch (Exception e) {
            logger.error("[TOOL][plan_itinerary] 规划异常", e);
            return errorResult("规划过程发生异常：" + e.getMessage());
        }
    }

    // ============================== 入参组装 ==============================

    private JSONObject buildInputData(String origin, String destination, String departureDate,
                                      String returnDate, String preferences, String candidates, String policy) {
        JSONObject data = new JSONObject();
        data.put("user_request", new JSONObject(Map.of(
                "origin", origin, "destination", destination,
                "departure_date", departureDate, "return_date", returnDate)));
        data.put("preferences", nullToEmpty(preferences));
        data.put("dimension_weights", new JSONObject(Map.of(
                "time", DEFAULT_WEIGHT_TIME, "price", DEFAULT_WEIGHT_PRICE, "preference", DEFAULT_WEIGHT_PREFERENCE)));
        data.put("candidates", parseJsonOrEmpty(candidates));
        data.put("policy", parseJsonOrEmpty(policy));
        return data;
    }

    private String buildSummary(JSONObject result, CalcContext ctx, String userId) {
        JSONArray proposals = result.getJSONArray("proposals");
        JSONObject tags = new JSONObject();
        for (int i = 0; i < proposals.size(); i++) {
            JSONObject p = proposals.getJSONObject(i);
            JSONArray tg = p.getJSONArray("tags");
            String firstTag = (tg != null && !tg.isEmpty()) ? tg.getString(0) : "";
            tags.put(firstTag, p.getJSONObject("scores").get("overall"));
        }
        logger.info("[TOOL][plan_itinerary] 完成，combo={}, proposals={}", ctx.comboCount, proposals.size());
        return new JSONObject(Map.of(
                "ok", true,
                "redis_key", ItineraryPlanStore.keyOf(userId),
                "combo_count", ctx.comboCount,
                "proposal_count", proposals.size(),
                "tags", tags)).toJSONString();
    }

    // ============================== 计算上下文构建 ==============================

    private CalcContext buildCalcContext(JSONObject data) {
        CalcContext ctx = new CalcContext();
        JSONObject userRequest = nullSafe(data.getJSONObject("user_request"));
        JSONObject policy = nullSafe(data.getJSONObject("policy"));
        JSONObject candidates = nullSafe(data.getJSONObject("candidates"));

        JSONArray transports = nullSafeArr(candidates.getJSONArray("transport_options"));
        JSONArray hotels = nullSafeArr(candidates.getJSONArray("hotel_options"));

        if (transports.isEmpty() || hotels.isEmpty()) {
            ctx.error = "输入缺少 transport_options 或 hotel_options，无法规划。";
            return ctx;
        }

        // 1) 切分去/返程
        List<JSONObject> outbound = new ArrayList<>();
        List<JSONObject> inbound = new ArrayList<>();
        splitTransport(transports, userRequest, outbound, inbound);

        if (outbound.isEmpty() || inbound.isEmpty()) {
            ctx.error = String.format("去程或返程为空（去程 %d 条 / 返程 %d 条），请检查 transport_options 的 direction/时间字段。",
                    outbound.size(), inbound.size());
            return ctx;
        }

        List<JSONObject> hotelList = IntStream.range(0, hotels.size())
                .mapToObj(hotels::getJSONObject).toList();

        // 2) 笛卡尔积 + 过滤
        List<JSONObject> combos = buildCombinations(outbound, hotelList, inbound, policy);
        if (combos.isEmpty()) {
            ctx.error = "所有组合都被过滤，可能是返程时间都早于去程到达。";
            return ctx;
        }

        // 3) 归一化时间/价格
        normalizeTimeAndPrice(combos);

        // 4) 构建单项索引
        outbound.forEach(t -> ctx.legTransport.putIfAbsent(getStr(t, "id"), toLegView(t)));
        inbound.forEach(t -> ctx.legTransport.putIfAbsent(getStr(t, "id"), toLegView(t)));
        hotelList.forEach(h -> ctx.legHotels.putIfAbsent(getStr(h, "id"), toHotelView(h)));

        ctx.userRequest = userRequest;
        ctx.preferences = nullToEmpty(data.getString("preferences"));
        ctx.weights = normalizeWeights(nullSafe(data.getJSONObject("dimension_weights")));
        ctx.combos = combos;
        ctx.comboCount = combos.size();
        return ctx;
    }

    // ============================== 切分去/返程 ==============================

    private void splitTransport(JSONArray transports, JSONObject userRequest,
                                List<JSONObject> outbound, List<JSONObject> inbound) {
        for (int i = 0; i < transports.size(); i++) {
            JSONObject t = new JSONObject(transports.getJSONObject(i));
            t.put("_transit_min", durationMinutes(getStr(t, "departure_time"), getStr(t, "arrival_time")));
            if ("outbound".equals(classifyDirection(t, userRequest))) {
                outbound.add(t);
            } else {
                inbound.add(t);
            }
        }
    }

    /**
     * 判断一条 transport 属于去程还是返程。
     * 优先级：显式 direction &gt; 出发城市匹配 &gt; 出发日期匹配。
     */
    private static String classifyDirection(JSONObject t, JSONObject userRequest) {
        String direction = trimOrEmpty(getStr(t, "direction")).toLowerCase();

        if (Set.of("outbound", "去程", "go", "depart").contains(direction)) {
            return "outbound";
        }
        if (Set.of("return", "返程", "back", "inbound").contains(direction)) {
            return "return";
        }

        // 按出发城市匹配
        String originCity = trimOrEmpty(getStr(userRequest, "origin"));
        String tOrigin = trimOrEmpty(getStr(t, "origin"));
        if (!originCity.isEmpty() && !tOrigin.isEmpty()) {
            if (cityMatches(tOrigin, originCity)) {
                return "outbound";
            }
            String destCity = trimOrEmpty(getStr(userRequest, "destination"));
            if (!destCity.isEmpty() && cityMatches(tOrigin, destCity)) {
                return "return";
            }
        }

        // 按日期匹配
        LocalDateTime depDate = parseDt(getStr(t, "departure_time"));
        LocalDateTime retDate = parseDt(trimOrEmpty(getStr(userRequest, "return_date")) + "T00:00");
        if (depDate != null && retDate != null && !depDate.toLocalDate().isBefore(retDate.toLocalDate())) {
            return "return";
        }
        LocalDateTime depReq = parseDt(trimOrEmpty(getStr(userRequest, "departure_date")) + "T00:00");
        if (depDate != null && depReq != null && !depDate.toLocalDate().isAfter(depReq.toLocalDate())) {
            return "outbound";
        }
        return "outbound";
    }

    /** 城市名模糊匹配：任一以另一开头（至少比较 2 字符） */
    private static boolean cityMatches(String city1, String city2) {
        String prefix1 = city1.length() >= 2 ? city1.substring(0, 2) : city1;
        return city1.startsWith(city2) || city2.startsWith(prefix1);
    }

    // ============================== 笛卡尔积 + 过滤 ==============================

    private List<JSONObject> buildCombinations(List<JSONObject> outbound, List<JSONObject> hotelList,
                                               List<JSONObject> inbound, JSONObject policy) {
        List<JSONObject> combos = new ArrayList<>();
        for (JSONObject ob : outbound) {
            for (JSONObject h : hotelList) {
                for (JSONObject rb : inbound) {
                    JSONObject combo = tryBuildCombo(ob, h, rb, policy);
                    if (combo != null) {
                        combos.add(combo);
                    }
                }
            }
        }
        return combos;
    }

    private JSONObject tryBuildCombo(JSONObject ob, JSONObject h, JSONObject rb, JSONObject policy) {
        LocalDateTime obArr = parseDt(getStr(ob, "arrival_time"));
        LocalDateTime rbDep = parseDt(getStr(rb, "departure_time"));
        // 返程出发必须晚于去程到达
        if (obArr != null && rbDep != null && !rbDep.isAfter(obArr)) {
            return null;
        }

        Integer obTransit = getInt(ob, "_transit_min");
        Integer rbTransit = getInt(rb, "_transit_min");
        int totalTransit = orZero(obTransit) + orZero(rbTransit);
        int nights = Math.max(1, getIntOr(h, "nights", 1));
        double hotelTotal = getDblOr(h, "price_per_night", 0) * nights;
        double totalPrice = getDblOr(ob, "price", 0) + getDblOr(rb, "price", 0) + hotelTotal;
        Double stayHours = (obArr != null && rbDep != null)
                ? round1((rbDep.toEpochSecond(ZoneOffset.UTC) - obArr.toEpochSecond(ZoneOffset.UTC)) / 3600.0)
                : null;

        PolicyResult pr = checkPolicy(ob, h, rb, totalPrice, policy);

        JSONObject combo = new JSONObject();
        combo.put("combo_id", getStr(ob, "id") + "|" + getStr(h, "id") + "|" + getStr(rb, "id"));
        combo.put("outbound_id", getStr(ob, "id"));
        combo.put("hotel_id", getStr(h, "id"));
        combo.put("return_id", getStr(rb, "id"));
        combo.put("total_price", round2(totalPrice));
        combo.put("outbound_transit_min", obTransit);
        combo.put("return_transit_min", rbTransit);
        combo.put("total_transit_min", totalTransit);
        combo.put("total_transit_hhmm", formatDuration(totalTransit));
        combo.put("stay_hours", stayHours);
        combo.put("policy_score", pr.score());
        combo.put("policy_violations", pr.violations());
        combo.put("warnings", pr.warnings());
        return combo;
    }

    // ============================== 归一化 ==============================

    private void normalizeTimeAndPrice(List<JSONObject> combos) {
        DoubleSummaryStatistics timeStats = combos.stream()
                .mapToDouble(c -> c.getDoubleValue("total_transit_min")).summaryStatistics();
        DoubleSummaryStatistics priceStats = combos.stream()
                .mapToDouble(c -> c.getDoubleValue("total_price")).summaryStatistics();

        for (JSONObject c : combos) {
            c.put("time_score", minmaxScore(c.getDouble("total_transit_min"),
                    timeStats.getMin(), timeStats.getMax(), false));
            c.put("price_score", minmaxScore(c.getDouble("total_price"),
                    priceStats.getMin(), priceStats.getMax(), false));
        }
    }

    // ============================== Policy 软约束 ==============================

    /**
     * 纯数值/枚举比较，命中即记 violation/warning，绝不剔除组合。
     */
    private PolicyResult checkPolicy(JSONObject ob, JSONObject hotel, JSONObject rb,
                                     double totalPrice, JSONObject policy) {
        JSONArray violations = new JSONArray();
        JSONArray warnings = new JSONArray();

        Double hotelLimit = getDbl(policy, "hotel_limit_per_night");
        Double hotelPrice = getDbl(hotel, "price_per_night");
        if (hotelLimit != null && hotelPrice != null && hotelPrice > hotelLimit) {
            violations.add(String.format("酒店单晚 %s 超过限额 %s", fmtNum(hotelPrice), fmtNum(hotelLimit)));
        }

        Double budgetLimit = getDbl(policy, "total_budget_limit");
        if (budgetLimit != null && totalPrice > budgetLimit) {
            violations.add(String.format("总价 %.0f 超过总预算 %s", totalPrice, fmtNum(budgetLimit)));
        }

        // 舱位校验
        checkCabinPolicy(ob, "去程", policy, warnings);
        checkCabinPolicy(rb, "返程", policy, warnings);

        double score = POLICY_BASE_SCORE
                       - POLICY_VIOLATION_PENALTY * violations.size()
                       - POLICY_WARNING_PENALTY * warnings.size();
        return new PolicyResult(violations, warnings, round2(Math.max(0.0, score)));
    }

    private void checkCabinPolicy(JSONObject leg, String label, JSONObject policy, JSONArray warnings) {
        String cabin = trimOrEmpty(getStr(leg, "cabin_class"));
        if (cabin.isEmpty()) {
            return;
        }
        String policyKey = "train".equals(getStr(leg, "type")) ? "transport_class" : "flight_class";
        String allowed = getStr(policy, policyKey);
        if (allowed == null || allowed.isEmpty()) {
            return;
        }

        List<String> allowedSet = Arrays.stream(allowed.replace("，", ",").replace("/", ",").split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (!allowedSet.isEmpty() && !cabinAllowed(cabin, allowedSet)) {
            warnings.add(String.format("%s舱位 '%s' 不在允许范围 %s", label, cabin, allowed));
        }
    }

    private static boolean cabinAllowed(String cabin, List<String> allowedSet) {
        String cabinLower = cabin.toLowerCase();
        String cabinNorm = CABIN_ALIASES.getOrDefault(cabinLower, cabinLower);
        for (String a : allowedSet) {
            String aLower = a.toLowerCase();
            String aNorm = CABIN_ALIASES.getOrDefault(aLower, aLower);
            // 等价匹配 或 包含匹配
            if (cabinNorm.equals(aNorm) || aLower.contains(cabinLower) || cabinLower.contains(aLower)) {
                return true;
            }
        }
        return false;
    }

    // ============================== 排序 + 选代表 ==============================

    private JSONObject rankAndBuildResult(CalcContext ctx, JSONObject scores) {
        JSONObject tScores = nullSafe(scores.getJSONObject("transport_scores"));
        JSONObject hScores = nullSafe(scores.getJSONObject("hotel_scores"));
        double wTime = ctx.weights.getDoubleValue("time");
        double wPrice = ctx.weights.getDoubleValue("price");
        double wPref = ctx.weights.getDoubleValue("preference");

        // 为每个 combo 计算偏好分和综合分
        for (JSONObject c : ctx.combos) {
            ScoreEntry obS = resolveScore(tScores, c.getString("outbound_id"));
            ScoreEntry hS = resolveScore(hScores, c.getString("hotel_id"));
            ScoreEntry rbS = resolveScore(tScores, c.getString("return_id"));
            double pref = round2((obS.score() + hS.score() + rbS.score()) / 3.0);
            c.put("preference_score", pref);
            c.put("preference_basis", mergeBasis(obS, hS, rbS));
            c.put("overall_score", round2(
                    c.getDoubleValue("time_score") * wTime
                    + c.getDoubleValue("price_score") * wPrice
                    + pref * wPref));
        }

        // 选代表并合并同 combo 的 tag
        JSONArray proposals = buildProposals(ctx);

        // 综合最佳排前
        proposals.sort((a, b) -> Double.compare(
                ((JSONObject) b).getJSONObject("scores").getDoubleValue("overall"),
                ((JSONObject) a).getJSONObject("scores").getDoubleValue("overall")));

        JSONObject meta = new JSONObject();
        meta.put("generated_at", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        meta.put("dimension_weights", ctx.weights);
        meta.put("combo_count", ctx.comboCount);

        JSONObject result = new JSONObject();
        result.put("meta", meta);
        result.put("user_request", ctx.userRequest);
        result.put("preferences", ctx.preferences);
        result.put("proposals", proposals);
        return result;
    }

    private JSONArray buildProposals(CalcContext ctx) {
        List<JSONObject> combos = ctx.combos;
        Map<String, JSONObject> picks = Map.of(
                "综合最佳", pickBest(combos, "overall_score", "preference_score", true),
                "时间最短", pickBest(combos, "total_transit_min", "total_price", false),
                "价格最低", pickBest(combos, "total_price", "total_transit_min", false),
                "最符合偏好", pickBest(combos, "preference_score", "overall_score", true));

        // 按 combo_id 去重，同一 combo 合并 tags
        Map<String, JSONObject> mergedCombo = new LinkedHashMap<>();
        Map<String, JSONArray> mergedTags = new LinkedHashMap<>();
        for (String tag : TAG_ORDER) {
            JSONObject c = picks.get(tag);
            String cid = c.getString("combo_id");
            mergedCombo.putIfAbsent(cid, c);
            mergedTags.computeIfAbsent(cid, k -> new JSONArray()).add(tag);
        }

        JSONArray proposals = new JSONArray();
        for (var entry : mergedCombo.entrySet()) {
            JSONObject c = entry.getValue();
            JSONObject p = new JSONObject();
            p.put("tags", mergedTags.get(entry.getKey()));
            p.put("outbound", ctx.legTransport.get(c.getString("outbound_id")));
            p.put("hotel", ctx.legHotels.get(c.getString("hotel_id")));
            p.put("return", ctx.legTransport.get(c.getString("return_id")));
            p.put("metrics", new JSONObject(Map.of(
                    "total_price", c.get("total_price"),
                    "total_transit_min", c.get("total_transit_min"),
                    "total_transit_hhmm", c.get("total_transit_hhmm"),
                    "stay_hours", c.get("stay_hours") != null ? c.get("stay_hours") : "")));
            p.put("scores", new JSONObject(Map.of(
                    "time", c.get("time_score"), "price", c.get("price_score"),
                    "preference", c.get("preference_score"), "policy", c.get("policy_score"),
                    "overall", c.get("overall_score"))));
            p.put("preference_basis", c.get("preference_basis"));
            p.put("policy_violations", c.get("policy_violations"));
            p.put("warnings", c.get("warnings"));
            proposals.add(p);
        }
        return proposals;
    }

    /**
     * 统一的"选最优"方法：higherIsBetter=true 取最大值，false 取最小值，key2 做平局打破。
     */
    private static JSONObject pickBest(List<JSONObject> combos, String key1, String key2, boolean higherIsBetter) {
        JSONObject best = null;
        for (JSONObject c : combos) {
            if (best == null) {
                best = c;
                continue;
            }
            int cmp = Double.compare(c.getDoubleValue(key1), best.getDoubleValue(key1));
            if (cmp == 0) {
                // 平局：次要维度总是取更优方向（时间/价格→小好，偏好/综合→大好，这里简化用 higherIsBetter 同向）
                cmp = Double.compare(c.getDoubleValue(key2), best.getDoubleValue(key2));
            }
            if (higherIsBetter ? cmp > 0 : cmp < 0) {
                best = c;
            }
        }
        return best;
    }

    // ============================== 偏好分解析 ==============================

    private static ScoreEntry resolveScore(JSONObject scoreMap, String legId) {
        Object v = scoreMap.get(legId);
        if (v == null) {
            return ScoreEntry.EMPTY;
        }
        if (v instanceof Number num) {
            return new ScoreEntry(num.doubleValue(), new JSONArray());
        }
        JSONObject o = scoreMap.getJSONObject(legId);
        double score = getDblOr(o, "score", 0);
        JSONArray basis = o.getJSONArray("basis");
        return new ScoreEntry(score, basis != null ? basis : new JSONArray());
    }

    private static JSONArray mergeBasis(ScoreEntry obS, ScoreEntry hS, ScoreEntry rbS) {
        JSONArray result = new JSONArray();
        String[] prefixes = {"去程", "住宿", "返程"};
        ScoreEntry[] entries = {obS, hS, rbS};
        for (int i = 0; i < entries.length; i++) {
            JSONArray basis = entries[i].basis();
            for (int j = 0; j < basis.size(); j++) {
                result.add(prefixes[i] + ": " + basis.getString(j));
            }
        }
        return result;
    }

    // ============================== 视图构建 ==============================

    private static JSONObject toLegView(JSONObject t) {
        JSONObject view = copyFields(t, LEG_FIELDS);
        Integer transit = getInt(t, "_transit_min");
        view.put("transit_min", transit);
        view.put("transit_hhmm", formatDuration(transit));
        return view;
    }

    private static JSONObject toHotelView(JSONObject h) {
        return copyFields(h, HOTEL_FIELDS);
    }

    private static JSONObject copyFields(JSONObject source, List<String> fields) {
        JSONObject target = new JSONObject(fields.size());
        for (String field : fields) {
            target.put(field, source.get(field));
        }
        return target;
    }

    private static JSONObject normalizeWeights(JSONObject weights) {
        double time = getDblOr(weights, "time", 1);
        double price = getDblOr(weights, "price", 1);
        double pref = getDblOr(weights, "preference", 1);
        double sum = time + price + pref;
        if (sum <= 0) {
            return new JSONObject(Map.of("time", 1.0 / 3, "price", 1.0 / 3, "preference", 1.0 / 3));
        }
        return new JSONObject(Map.of("time", time / sum, "price", price / sum, "preference", pref / sum));
    }

    // ============================== 通用工具方法 ==============================

    private static LocalDateTime parseDt(String s) {
        if (s == null) {
            return null;
        }
        String v = s.trim().replace("Z", "");
        if (v.isEmpty()) {
            return null;
        }
        for (DateTimeFormatter fmt : DT_FORMATS) {
            try {
                return LocalDateTime.parse(v, fmt);
            } catch (Exception ignore) {
                // try next
            }
        }
        try {
            return LocalDate.parse(v, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay();
        } catch (Exception ignore) {
            return null;
        }
    }

    private static Integer durationMinutes(String dep, String arr) {
        LocalDateTime d = parseDt(dep);
        LocalDateTime a = parseDt(arr);
        if (d == null || a == null) {
            return null;
        }
        return (int) Math.round((a.toEpochSecond(ZoneOffset.UTC) - d.toEpochSecond(ZoneOffset.UTC)) / 60.0);
    }

    private static String formatDuration(Integer minutes) {
        if (minutes == null) {
            return null;
        }
        return String.format("%dh%02dm", minutes / 60, minutes % 60);
    }

    /**
     * 线性归一化到 [0,100]。higherIsBetter=false → 值越小分越高。lo==hi 时返回 100。
     */
    private static double minmaxScore(Double value, double lo, double hi, boolean higherIsBetter) {
        if (value == null) {
            return 0.0;
        }
        if (Double.compare(hi, lo) == 0) {
            return 100.0;
        }
        double ratio = (value - lo) / (hi - lo);
        if (!higherIsBetter) {
            ratio = 1.0 - ratio;
        }
        return round2(Math.clamp(ratio, 0.0, 1.0) * 100.0);
    }

    private static double round2(double x) {
        return Math.round(x * 100.0) / 100.0;
    }

    private static double round1(double x) {
        return Math.round(x * 10.0) / 10.0;
    }

    private static String fmtNum(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    // ============================== JSON 访问辅助 ==============================

    private static String getStr(JSONObject o, String key) {
        Object v = o.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static Double getDbl(JSONObject o, String key) {
        Object v = o.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Number num) {
            return num.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(v).trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static double getDblOr(JSONObject o, String key, double def) {
        Double v = getDbl(o, key);
        return v != null ? v : def;
    }

    private static int getIntOr(JSONObject o, String key, int def) {
        Double v = getDbl(o, key);
        return v != null ? (int) Math.round(v) : def;
    }

    private static Integer getInt(JSONObject o, String key) {
        Object v = o.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Number num) {
            return num.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static int orZero(Integer val) {
        return val != null ? val : 0;
    }

    private static String trimOrEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static JSONObject nullSafe(JSONObject o) {
        return o != null ? o : new JSONObject();
    }

    private static JSONArray nullSafeArr(JSONArray a) {
        return a != null ? a : new JSONArray();
    }

    private static JSONObject parseJsonOrEmpty(String json) {
        return (json != null && !json.isBlank()) ? JSON.parseObject(JsonUtil.fixJson(json)) : new JSONObject();
    }

    private static String errorResult(String msg) {
        return new JSONObject(Map.of("ok", false, "error", msg)).toJSONString();
    }
}
