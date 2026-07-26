package com.gogo.travel.agent.tools;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.gogo.travel.agent.context.AgentSessionContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 行程方案最终审核工具。
 * 对规划好的行程进行结构化校验，涵盖行程完整性、时间安排、
 * 出发地目的地核实、预算情况、路径合理性等维度。
 *
 * @author Hollis
 */
@Component
public class ItineraryReviewTools {

    private static final Logger logger = LoggerFactory.getLogger(ItineraryReviewTools.class);

    // ============================== 常量 ==============================

    private static final int MIN_DOMESTIC_TRANSFER_MINUTES = 60;
    private static final int MIN_INTL_TRANSFER_MINUTES = 90;
    private static final int MIN_ARRIVAL_BUFFER_MINUTES = 60;
    /** 返程前最低缓冲时间（分钟） */
    private static final int MIN_RETURN_BUFFER_MINUTES = 60;
    /** 酒店距主要活动场所告警阈值（公里） */
    private static final double MAX_HOTEL_DISTANCE_KM = 15.0;

    /** 审核状态对综合分的权重 */
    private static final double WEIGHT_PASS = 1.0;
    private static final double WEIGHT_WARNING = 0.7;
    private static final double WEIGHT_FAIL = 0.0;

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("H:mm");

    private final ItineraryPlanStore itineraryPlanStore;

    public ItineraryReviewTools(ItineraryPlanStore itineraryPlanStore) {
        this.itineraryPlanStore = itineraryPlanStore;
    }

    // ============================== 内部数据结构 ==============================

    private record CheckResult(String dimension, String status, String detail) {
        JSONObject toJson() {
            return new JSONObject(Map.of("dimension", dimension, "status", status, "detail", detail));
        }
    }

    // ============================== @Tool 入口 ==============================

    @Tool(
            name = "review_planner_result",
            description = "从行程规划工具（plan_itinerary）存入 Redis 的规划结果中读取多方案并批量结构化审核（五维：行程完整性/时间/出发目的地/预算/路径，不含个人偏好）。" +
                          "规划结果按用户隔离存储在 Redis 中，无需也无法传入存储位置。" +
                          "工具直接从 Redis 读取解析，无需把庞大的方案 JSON 作为参数传入，避免上下文膨胀与 JSON 解析失败。" +
                          "返回 reviews 数组（每项含 proposal_id、overall_status、checks、issues、suggestions）、best_proposal_id 与 summary。"
    )
    public String reviewPlannerResult(
            @ToolParam(name = "policy",
                    description = "差旅政策JSON（hotel_limit_per_night / transport_class / total_budget_limit 等）",
                    required = false)
            String policy,

            AgentSessionContext sessionCtx) {

        String userId = sessionCtx != null ? sessionCtx.getUserId() : null;
        String redisKey = ItineraryPlanStore.keyOf(userId);
        logger.info("[TOOL][review_planner_result] 从 Redis 读取规划结果并审核: {}", redisKey);

        String content;
        try {
            content = itineraryPlanStore.load(userId);
        } catch (Exception e) {
            logger.error("[TOOL][review_planner_result] Redis 读取失败: {} - {}", redisKey, e.getMessage());
            return failResponse("Redis 读取规划结果失败 " + redisKey + "：" + e.getMessage()
                                + "。请确认 plan_itinerary 已成功生成规划结果。");
        }

        if (content == null) {
            logger.error("[TOOL][review_planner_result] Redis 中无规划结果: {}", redisKey);
            return failResponse("Redis 中未找到规划结果（Key: " + redisKey + "）。请确认 plan_itinerary 已成功执行。");
        }

        try {
            JSONObject resultJson = JSON.parseObject(content);
            JSONArray rawProposals = resultJson.getJSONArray("proposals");
            if (rawProposals == null || rawProposals.isEmpty()) {
                return failResponse("Redis 规划结果中未找到 proposals 方案数组（Key: " + redisKey + "）");
            }

            JSONObject userRequest = resultJson.getJSONObject("user_request");
            JSONArray proposalsArr = new JSONArray();
            for (int i = 0; i < rawProposals.size(); i++) {
                proposalsArr.add(convertPlannerProposal(rawProposals.getJSONObject(i), userRequest, i));
            }

            JSONObject policyJson = policy != null ? JSON.parseObject(policy) : new JSONObject();
            return doReviewProposals(proposalsArr, policyJson);
        } catch (Exception e) {
            logger.error("[TOOL][review_planner_result] 解析规划结果失败: {}", e.getMessage());
            return failResponse("Redis 规划结果格式异常：" + e.getMessage());
        }
    }

    // ============================== 批量审核 ==============================

    private String doReviewProposals(JSONArray proposalsArr, JSONObject policyJson) {
        JSONArray reviewsArr = new JSONArray();
        String bestProposalId = null;
        int bestScore = -1;
        List<String> failIds = new ArrayList<>();
        List<String> warningIds = new ArrayList<>();
        List<String> passIds = new ArrayList<>();

        for (int i = 0; i < proposalsArr.size(); i++) {
            JSONObject proposal = proposalsArr.getJSONObject(i);
            String proposalId = proposal.getString("proposal_id");
            if (proposalId == null) {
                proposalId = "P" + (i + 1);
            }

            JSONObject itineraryView = proposal.getJSONObject("itinerary_view");
            if (itineraryView == null) {
                itineraryView = proposal;
            }

            JSONObject singleResult = reviewSingle(itineraryView, policyJson);
            singleResult.put("proposal_id", proposalId);
            putIfNotNull(singleResult, "proposal_label", proposal.getString("label"));

            JSONObject scoresObj = proposal.getJSONObject("scores");
            if (scoresObj != null) {
                singleResult.put("planner_scores", scoresObj);
            }
            reviewsArr.add(singleResult);

            // 分类统计
            String status = singleResult.getString("overall_status");
            switch (status) {
                case "pass" -> passIds.add(proposalId);
                case "warning" -> warningIds.add(proposalId);
                default -> failIds.add(proposalId);
            }

            // 选出 best
            int effectiveScore = computeEffectiveScore(status, scoresObj);
            if (effectiveScore > bestScore) {
                bestScore = effectiveScore;
                bestProposalId = proposalId;
            }
        }

        JSONObject result = new JSONObject();
        result.put("reviews", reviewsArr);
        result.put("best_proposal_id", bestProposalId);
        result.put("summary", new JSONObject(Map.of(
                "total", reviewsArr.size(),
                "pass_count", passIds.size(), "warning_count", warningIds.size(), "fail_count", failIds.size(),
                "pass_ids", passIds, "warning_ids", warningIds, "fail_ids", failIds)));
        result.put("issues", collectField(reviewsArr, "issues"));
        result.put("suggestions", collectField(reviewsArr, "suggestions"));

        logger.info("[TOOL][review] 批量审核完成: total={}, pass={}, warning={}, fail={}, best={}",
                reviewsArr.size(), passIds.size(), warningIds.size(), failIds.size(), bestProposalId);
        return result.toJSONString();
    }

    // ============================== 方案转换 ==============================

    private JSONObject convertPlannerProposal(JSONObject raw, JSONObject userRequest, int index) {
        JSONObject out = new JSONObject();

        JSONArray tags = raw.getJSONArray("tags");
        String label = (tags != null && !tags.isEmpty())
                ? String.join("/", tags.toJavaList(String.class)) : ("方案" + (index + 1));
        out.put("proposal_id", "P" + (index + 1));
        out.put("label", label);
        out.put("scores", raw.getJSONObject("scores"));
        out.put("itinerary_view", buildItineraryView(raw, userRequest));
        return out;
    }

    private JSONObject buildItineraryView(JSONObject raw, JSONObject userRequest) {
        JSONObject view = new JSONObject();
        String depDate = null;
        String retDate = null;

        if (userRequest != null) {
            view.put("origin", userRequest.getString("origin"));
            view.put("destination", userRequest.getString("destination"));
            depDate = userRequest.getString("departure_date");
            retDate = userRequest.getString("return_date");
            view.put("departure_date", depDate);
            view.put("return_date", retDate);
            view.put("approved_origin", userRequest.getString("origin"));
            view.put("approved_destination", userRequest.getString("destination"));
        }

        JSONObject metrics = raw.getJSONObject("metrics");
        if (metrics != null) {
            putIfNotNull(view, "total_budget", metrics.getDouble("total_price"));
            putIfNotNull(view, "total_transit_hhmm", metrics.getString("total_transit_hhmm"));
            putIfNotNull(view, "total_transit_min", metrics.getInteger("total_transit_min"));
        }

        JSONArray days = new JSONArray();
        // Day 1: 去程 + 住宿
        JSONObject day1 = new JSONObject();
        day1.put("date", depDate);
        putIfNotNull(day1, "outbound_transport", mapTransport(raw.getJSONObject("outbound")));
        putIfNotNull(day1, "hotel", mapHotel(raw.getJSONObject("hotel")));
        days.add(day1);
        // Day 2: 返程
        JSONObject day2 = new JSONObject();
        day2.put("date", retDate);
        putIfNotNull(day2, "return_transport", mapTransport(raw.getJSONObject("return")));
        days.add(day2);
        view.put("days", days);
        return view;
    }

    private JSONObject mapTransport(JSONObject t) {
        if (t == null) {
            return null;
        }
        JSONObject o = new JSONObject();
        o.put("type", t.getString("type"));
        o.put("carrier", t.getString("carrier"));
        o.put("code", t.getString("code"));
        o.put("departure", extractHm(t.getString("departure_time")));
        o.put("arrival", extractHm(t.getString("arrival_time")));
        putIfNotNull(o, "price", t.getDouble("price"));
        o.put("seat_class", t.getString("cabin_class"));
        putIfNotNull(o, "is_direct", t.getBoolean("is_direct"));
        return o;
    }

    private JSONObject mapHotel(JSONObject h) {
        if (h == null) {
            return null;
        }
        JSONObject o = new JSONObject();
        o.put("name", h.getString("name"));
        putIfNotNull(o, "room_type", h.getString("room_type"));
        putIfNotNull(o, "nights", h.getInteger("nights"));
        putIfNotNull(o, "price_per_night", h.getDouble("price_per_night"));
        putIfNotNull(o, "brand", h.getString("brand"));
        putIfNotNull(o, "distance_to_main_venue_km", h.getDouble("distance_to_dest_km"));
        return o;
    }

    // ============================== 单方案五维审核 ==============================

    private JSONObject reviewSingle(JSONObject itineraryJson, JSONObject policyJson) {
        List<CheckResult> checks = new ArrayList<>();
        List<String> issues = new ArrayList<>();
        List<String> suggestions = new ArrayList<>();

        checkCompleteness(itineraryJson, checks, issues, suggestions);
        checkTimingValidity(itineraryJson, checks, issues, suggestions);
        checkOriginDestination(itineraryJson, checks, issues, suggestions);
        checkBudgetCompliance(itineraryJson, policyJson, checks, issues, suggestions);
        checkRouteRationality(itineraryJson, checks, issues, suggestions);

        String overallStatus = computeOverallStatus(checks);

        JSONObject result = new JSONObject();
        result.put("overall_status", overallStatus);
        result.put("checks", checks.stream().map(CheckResult::toJson).toList());
        result.put("issues", issues);
        result.put("suggestions", suggestions);
        return result;
    }

    // ─── 1. 行程完整性 ───

    private void checkCompleteness(JSONObject itinerary, List<CheckResult> checks,
                                   List<String> issues, List<String> suggestions) {
        List<String> problems = new ArrayList<>();

        if (isBlank(itinerary.getString("origin"))) {
            problems.add("出发地（origin）未填写");
        }
        if (isBlank(itinerary.getString("destination"))) {
            problems.add("目的地（destination）未填写");
        }
        if (isBlank(itinerary.getString("departure_date"))) {
            problems.add("去程日期（departure_date）未填写");
        }
        if (isBlank(itinerary.getString("return_date"))) {
            problems.add("返程日期（return_date）未填写");
        }

        JSONArray days = itinerary.getJSONArray("days");
        if (days == null || days.isEmpty()) {
            problems.add("行程中无每日安排（days 为空）");
        } else {
            checkDaysCompleteness(days, problems, suggestions);
        }

        if (problems.isEmpty()) {
            checks.add(new CheckResult("行程完整性", "pass", "去程/返程交通、住宿、活动安排均完整"));
        } else {
            issues.addAll(problems);
            checks.add(new CheckResult("行程完整性", "fail", "存在缺失项：" + String.join("；", problems)));
        }
    }

    private void checkDaysCompleteness(JSONArray days, List<String> problems, List<String> suggestions) {
        boolean hasOutbound = false;
        boolean hasReturn = false;
        int nightsWithoutHotel = 0;
        int daysWithoutActivities = 0;

        for (int i = 0; i < days.size(); i++) {
            JSONObject day = days.getJSONObject(i);
            if (day.containsKey("outbound_transport")) {
                hasOutbound = true;
            }
            if (day.containsKey("return_transport")) {
                hasReturn = true;
            }
            JSONArray activities = day.getJSONArray("activities");
            if (activities == null || activities.isEmpty()) {
                daysWithoutActivities++;
            }
            // 最后一天不需要住宿（当天返回）
            if (i < days.size() - 1 && !day.containsKey("hotel")) {
                nightsWithoutHotel++;
            }
        }

        if (!hasOutbound) {
            problems.add("去程交通未安排");
        }
        if (!hasReturn) {
            problems.add("返程交通未安排");
        }
        if (nightsWithoutHotel > 0) {
            problems.add(nightsWithoutHotel + " 个夜晚未安排住宿");
        }
        if (daysWithoutActivities > 0) {
            suggestions.add("有 " + daysWithoutActivities + " 天无具体活动安排，建议补充");
        }
    }

    // ─── 2. 时间安排合理性 ───

    private void checkTimingValidity(JSONObject itinerary, List<CheckResult> checks,
                                     List<String> issues, List<String> suggestions) {
        JSONArray days = itinerary.getJSONArray("days");
        if (days == null) {
            checks.add(new CheckResult("时间安排合理性", "warning", "无每日行程，无法校验时间"));
            return;
        }

        List<String> warnings = new ArrayList<>();
        for (int i = 0; i < days.size(); i++) {
            JSONObject day = days.getJSONObject(i);
            String dateStr = day.getString("date");

            if (i == 0 && day.containsKey("outbound_transport")) {
                checkArrivalBuffer(day, dateStr, warnings);
            }
            if (day.containsKey("return_transport")) {
                checkReturnBuffer(day, dateStr, warnings);
            }
            if (day.containsKey("outbound_transport")) {
                checkTransferTime(day, dateStr, warnings);
            }
        }

        if (warnings.isEmpty()) {
            checks.add(new CheckResult("时间安排合理性", "pass", "时间衔接正常，无明显冲突"));
        } else {
            issues.addAll(warnings);
            String status = warnings.stream().anyMatch(w -> w.contains("冲突")) ? "fail" : "warning";
            checks.add(new CheckResult("时间安排合理性", status, "存在时间问题：" + String.join("；", warnings)));
        }
    }

    /** 检查到达时间与首个活动之间的缓冲 */
    private void checkArrivalBuffer(JSONObject day, String dateStr, List<String> warnings) {
        JSONObject transport = day.getJSONObject("outbound_transport");
        String arrivalTime = transport.getString("arrival");
        JSONArray activities = day.getJSONArray("activities");
        if (arrivalTime == null || activities == null || activities.isEmpty()) {
            return;
        }
        String activityTime = extractTime(activities.getString(0));
        if (activityTime == null) {
            return;
        }
        int buffer = minutesBetween(arrivalTime, activityTime);
        if (buffer >= 0 && buffer < MIN_ARRIVAL_BUFFER_MINUTES) {
            warnings.add(dateStr + " 到达时间 " + arrivalTime + " 与首个活动 " + activityTime
                         + " 之间缓冲不足 " + MIN_ARRIVAL_BUFFER_MINUTES + " 分钟（实际约 " + buffer + " 分钟）");
        }
    }

    /** 检查最后活动与返程出发时间是否冲突 */
    private void checkReturnBuffer(JSONObject day, String dateStr, List<String> warnings) {
        JSONObject transport = day.getJSONObject("return_transport");
        String departureTime = transport.getString("departure");
        JSONArray activities = day.getJSONArray("activities");
        if (departureTime == null || activities == null || activities.isEmpty()) {
            return;
        }
        String activityTime = extractTime(activities.getString(activities.size() - 1));
        if (activityTime == null) {
            return;
        }
        int buffer = minutesBetween(activityTime, departureTime);
        if (buffer < 0) {
            warnings.add(dateStr + " 返程出发时间 " + departureTime
                         + " 早于最后活动时间 " + activityTime + "，存在时间冲突");
        } else if (buffer < MIN_RETURN_BUFFER_MINUTES) {
            warnings.add(dateStr + " 返程前缓冲时间仅 " + buffer + " 分钟，建议预留至少 "
                         + MIN_RETURN_BUFFER_MINUTES + " 分钟");
        }
    }

    /** 检查中转时间是否充裕 */
    private void checkTransferTime(JSONObject day, String dateStr, List<String> warnings) {
        JSONObject transport = day.getJSONObject("outbound_transport");
        JSONObject transfer = transport.getJSONObject("transfer");
        if (transfer == null) {
            return;
        }
        int transferMinutes = transfer.getIntValue("transfer_minutes", 0);
        if (transferMinutes <= 0) {
            return;
        }
        boolean isIntl = Boolean.TRUE.equals(transfer.getBoolean("international"));
        int minTransfer = isIntl ? MIN_INTL_TRANSFER_MINUTES : MIN_DOMESTIC_TRANSFER_MINUTES;
        if (transferMinutes < minTransfer) {
            warnings.add(dateStr + " 中转时间仅 " + transferMinutes + " 分钟，"
                         + (isIntl ? "国际" : "国内") + "中转建议 ≥ " + minTransfer + " 分钟");
        }
    }

    // ─── 3. 出发地 & 目的地核实 ───

    private void checkOriginDestination(JSONObject itinerary, List<CheckResult> checks,
                                        List<String> issues, List<String> suggestions) {
        String origin = itinerary.getString("origin");
        String destination = itinerary.getString("destination");
        String approvedOrigin = itinerary.getString("approved_origin");
        String approvedDestination = itinerary.getString("approved_destination");

        List<String> problems = new ArrayList<>();
        if (!cityMatches(origin, approvedOrigin)) {
            problems.add("出发地「" + origin + "」与审批单中「" + approvedOrigin + "」不一致");
        }
        if (!cityMatches(destination, approvedDestination)) {
            problems.add("目的地「" + destination + "」与审批单中「" + approvedDestination + "」不一致");
        }

        if (problems.isEmpty()) {
            checks.add(new CheckResult("出发地与目的地核实", "pass",
                    "出发地「" + origin + "」→ 目的地「" + destination + "」与审批单一致"));
        } else {
            issues.addAll(problems);
            checks.add(new CheckResult("出发地与目的地核实", "fail", String.join("；", problems)));
        }
    }

    /** 城市名包含式匹配，任一包含另一即视为一致 */
    private boolean cityMatches(String actual, String approved) {
        if (approved == null || actual == null) {
            return true; // 无审批信息时不做校验
        }
        return actual.contains(approved) || approved.contains(actual);
    }

    // ─── 4. 预算合规性 ───

    private void checkBudgetCompliance(JSONObject itinerary, JSONObject policy,
                                       List<CheckResult> checks, List<String> issues, List<String> suggestions) {
        if (policy.isEmpty()) {
            checks.add(new CheckResult("预算合规性", "warning", "未提供差旅政策，跳过预算校验"));
            suggestions.add("建议调用 check_travel_policy 获取差旅政策后再做预算校验");
            return;
        }

        Double hotelLimit = policy.getDouble("hotel_limit_per_night");
        Double budgetLimit = policy.getDouble("total_budget_limit");
        List<String> overBudget = new ArrayList<>();
        double totalCost = 0;

        JSONArray days = itinerary.getJSONArray("days");
        if (days != null) {
            totalCost = checkDaysBudget(days, hotelLimit, overBudget);
        }

        // 优先使用行程自带的 total_budget 字段
        Double total = itinerary.getDouble("total_budget");
        double itineraryTotal = total != null ? total : totalCost;
        if (budgetLimit != null && itineraryTotal > budgetLimit) {
            overBudget.add("总预算 ¥" + String.format("%.0f", itineraryTotal)
                           + " 超出差旅单批准金额 ¥" + String.format("%.0f", budgetLimit));
        }

        if (overBudget.isEmpty()) {
            checks.add(new CheckResult("预算合规性", "pass",
                    "预算合规，预计总费用 ¥" + String.format("%.0f", itineraryTotal)));
        } else {
            issues.addAll(overBudget);
            checks.add(new CheckResult("预算合规性", "warning", "存在超预算项：" + String.join("；", overBudget)));
            suggestions.add("超预算项目需在方案说明中注明原因，或调整为更经济的选项");
        }
    }

    private double checkDaysBudget(JSONArray days, Double hotelLimit, List<String> overBudget) {
        double totalCost = 0;
        for (int i = 0; i < days.size(); i++) {
            JSONObject day = days.getJSONObject(i);
            String dateStr = day.getString("date");

            JSONObject hotel = day.getJSONObject("hotel");
            if (hotel != null) {
                double pricePerNight = hotel.getDoubleValue("price_per_night");
                totalCost += pricePerNight;
                if (hotelLimit != null && pricePerNight > hotelLimit) {
                    overBudget.add(dateStr + " 住宿价格 ¥" + pricePerNight + " 超出政策上限 ¥" + hotelLimit);
                }
            }

            JSONObject outbound = day.getJSONObject("outbound_transport");
            if (outbound != null) {
                totalCost += outbound.getDoubleValue("price");
            }
            JSONObject returnT = day.getJSONObject("return_transport");
            if (returnT != null) {
                totalCost += returnT.getDoubleValue("price");
            }
        }
        return totalCost;
    }

    // ─── 5. 路径规划合理性 ───

    private void checkRouteRationality(JSONObject itinerary, List<CheckResult> checks,
                                       List<String> issues, List<String> suggestions) {
        JSONArray days = itinerary.getJSONArray("days");
        if (days == null || days.size() < 2) {
            checks.add(new CheckResult("路径规划合理性", "pass", "单日/短途行程，无需路径规划校验"));
            return;
        }

        List<String> warnings = new ArrayList<>();
        List<String> citySequence = extractCitySequence(days);
        checkCityBacktrack(citySequence, warnings);
        checkHotelDistance(days, warnings);

        if (warnings.isEmpty()) {
            checks.add(new CheckResult("路径规划合理性", "pass", "城市顺序合理，无明显绕路或折返问题"));
        } else {
            checks.add(new CheckResult("路径规划合理性", "warning", String.join("；", warnings)));
            suggestions.addAll(warnings);
        }
    }

    private List<String> extractCitySequence(JSONArray days) {
        List<String> cities = new ArrayList<>();
        for (int i = 0; i < days.size(); i++) {
            JSONObject hotel = days.getJSONObject(i).getJSONObject("hotel");
            if (hotel != null) {
                String location = hotel.getString("location");
                if (location != null && !location.isEmpty()) {
                    if (cities.isEmpty() || !cities.get(cities.size() - 1).equals(location)) {
                        cities.add(location);
                    }
                }
            }
        }
        return cities;
    }

    /** 检测 A->B->A 折返模式 */
    private void checkCityBacktrack(List<String> citySequence, List<String> warnings) {
        if (citySequence.size() < 4) {
            return;
        }
        for (int i = 0; i < citySequence.size() - 2; i++) {
            if (citySequence.get(i).equals(citySequence.get(i + 2))) {
                warnings.add("城市路径出现折返：" + citySequence.get(i) + " → "
                             + citySequence.get(i + 1) + " → " + citySequence.get(i + 2) + "，建议优化顺序");
            }
        }
    }

    private void checkHotelDistance(JSONArray days, List<String> warnings) {
        for (int i = 0; i < days.size(); i++) {
            JSONObject day = days.getJSONObject(i);
            JSONObject hotel = day.getJSONObject("hotel");
            if (hotel == null) {
                continue;
            }
            Double distance = hotel.getDouble("distance_to_main_venue_km");
            if (distance != null && distance > MAX_HOTEL_DISTANCE_KM) {
                String dateStr = day.getString("date");
                warnings.add(dateStr + " 酒店距主要活动场所约 " + String.format("%.1f", distance)
                             + " km，建议选择更近的住宿");
            }
        }
    }

    // ============================== 工具方法 ==============================

    private String computeOverallStatus(List<CheckResult> checks) {
        boolean hasFail = checks.stream().anyMatch(c -> "fail".equals(c.status()));
        boolean hasWarning = checks.stream().anyMatch(c -> "warning".equals(c.status()));
        if (hasFail) {
            return "fail";
        }
        return hasWarning ? "warning" : "pass";
    }

    private int computeEffectiveScore(String status, JSONObject plannerScores) {
        double weight = switch (status) {
            case "pass" -> WEIGHT_PASS;
            case "warning" -> WEIGHT_WARNING;
            default -> WEIGHT_FAIL;
        };
        double base = (plannerScores != null && plannerScores.getDouble("overall") != null)
                ? plannerScores.getDouble("overall") : 0.0;
        return (int) Math.round(base * weight);
    }

    /** 从 reviews 数组中提取指定字段（issues / suggestions），带 proposal_id 前缀 */
    private List<String> collectField(JSONArray reviews, String fieldName) {
        List<String> all = new ArrayList<>();
        for (int i = 0; i < reviews.size(); i++) {
            JSONObject r = reviews.getJSONObject(i);
            JSONArray items = r.getJSONArray(fieldName);
            if (items == null) {
                continue;
            }
            String pid = r.getString("proposal_id");
            for (int j = 0; j < items.size(); j++) {
                all.add("[" + pid + "] " + items.getString(j));
            }
        }
        return all;
    }

    /** 从 ISO 时间字符串中提取 HH:mm */
    private String extractHm(String time) {
        if (time == null) {
            return null;
        }
        int t = time.indexOf('T');
        String hm = t >= 0 ? time.substring(t + 1) : time;
        return (hm.length() >= 5 && hm.charAt(2) == ':') ? hm.substring(0, 5) : hm;
    }

    /** 从活动描述中提取时间，如 "09:30 会议" -> "09:30" */
    private String extractTime(String activity) {
        if (activity == null) {
            return null;
        }
        String[] parts = activity.trim().split("\\s+");
        return (parts.length > 0 && parts[0].matches("\\d{1,2}:\\d{2}")) ? parts[0] : null;
    }

    private int minutesBetween(String fromTime, String toTime) {
        try {
            LocalTime from = LocalTime.parse(fromTime, TIME_FORMAT);
            LocalTime to = LocalTime.parse(toTime, TIME_FORMAT);
            return (int) Duration.between(from, to).toMinutes();
        } catch (DateTimeParseException e) {
            return Integer.MAX_VALUE;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static void putIfNotNull(JSONObject target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private static String failResponse(String error) {
        return new JSONObject(Map.of(
                "overall_status", "fail",
                "error", error,
                "reviews", new JSONArray())).toJSONString();
    }
}
