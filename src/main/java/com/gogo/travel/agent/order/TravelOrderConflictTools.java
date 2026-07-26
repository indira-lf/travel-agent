package com.gogo.travel.agent.order;

import com.alibaba.fastjson2.JSON;
import com.gogo.travel.agent.context.AgentSessionContext;
import com.gogo.travel.business.order.entity.TravelOrder;
import com.gogo.travel.business.order.entity.TravelOrderStatus;
import com.gogo.travel.business.order.repo.TravelOrderRepository;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 行程冲突检测工具集。
 *
 * <p>提供 {@code check_travel_order_conflicts} 工具，覆盖两类检查：</p>
 * <ol>
 *   <li><b>同一员工时间重叠</b>：检测日期范围重叠的差旅单，区分同城（重复提交，LOW）和跨城（物理不可能，HIGH）。</li>
 *   <li><b>跨城市交通衔接合理性</b>：检测相邻差旅单之间的城市衔接，分为"同日衔接时间不足"（MEDIUM/HIGH）和"路径断裂"（MEDIUM）。</li>
 * </ol>
 *
 * <p>该工具为"建议"性质——是否真的阻断提交由 MasterAgent / LLM 决定；
 * ItineraryManageAgent 仍可在告知用户冲突后由用户确认后继续提交。</p>
 *
 * @author Hollis
 */
@Component
public class TravelOrderConflictTools {

    private static final Logger logger = LoggerFactory.getLogger(TravelOrderConflictTools.class);

    /** 视为"同日衔接"允许的最低分钟数（同城默认 0 分钟） */
    private static final int SAME_DAY_TIGHT_MINUTES = 8 * 60;

    /** 视为"绝对不可能"的天数：同日衔接但所需分钟超过该值 */
    private static final int SAME_DAY_IMPOSSIBLE_MINUTES = 24 * 60;

    @Autowired
    private TravelOrderRepository travelOrderRepository;
    @Autowired
    private CityTransitTimeService cityTransitTimeService;

    // ─────────────────────────────────────────────────────────────────────────
    // 行程冲突检测
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(name = "check_travel_order_conflicts",
          description = "检查用户当前要提交/修改的差旅单是否与已有生效中差旅单（DRAFT/SUBMITTED/APPROVED）存在冲突。"
                  + "覆盖两类检查：① 同一员工时间重叠（同城=LOW重复，跨城=HIGH物理不可能）；"
                  + "② 跨城市交通衔接合理性（同日衔接时间不足=TRANSIT_TOO_TIGHT；跨城但未衔接=DISCONNECTED_ROUTE）。"
                  + "返回 JSON 结构 {has_conflict, total_conflicts, conflicts:[...], summary}。"
                  + "建议在每次 submit_travel_approval 之前、以及修改差旅单的出发城市/目的地/日期时调用。")
    public String checkTravelOrderConflicts(
            AgentSessionContext sessionCtx,
            @ToolParam(name = "departure_city", description = "候选差旅单的出发城市") String departureCity,
            @ToolParam(name = "destination", description = "候选差旅单的目的地城市") String destination,
            @ToolParam(name = "departure_date", description = "候选差旅单的出发日期，YYYY-MM-DD") String departureDate,
            @ToolParam(name = "return_date", description = "候选差旅单的返回日期，YYYY-MM-DD") String returnDate,
            @ToolParam(name = "exclude_order_id", description = "需要排除的差旅单ID（修改场景下排除自身），可选", required = false) String excludeOrderId) {

        String userId = sessionCtx.getUserId();
        logger.info("[TOOL][check_travel_order_conflicts] userId={}, {}->{}, {}-{}, exclude={}",
                userId, departureCity, destination, departureDate, returnDate, excludeOrderId);

        // ── 入参校验 ──────────────────────────────────────────────────────────
        List<String> errors = new ArrayList<>();
        if (isBlank(userId))         {
            errors.add("user_id 不能为空");
        }
        if (isBlank(departureCity))  {
            errors.add("departure_city（出发城市）不能为空");
        }
        if (isBlank(destination))    {
            errors.add("destination（目的地）不能为空");
        }
        if (!isValidDate(departureDate)) {
            errors.add("departure_date（出发日期）格式错误或为空，需 YYYY-MM-DD");
        }
        if (!isValidDate(returnDate))    {
            errors.add("return_date（返回日期）格式错误或为空，需 YYYY-MM-DD");
        }
        if (!errors.isEmpty()) {
            return JSON.toJSONString(new ConflictReport() {{
                setHasConflict(false);
                setSummary("入参校验失败：" + String.join("；", errors));
            }});
        }

        LocalDate depDate;
        LocalDate retDate;
        try {
            depDate = LocalDate.parse(departureDate);
            retDate = LocalDate.parse(returnDate);
        } catch (DateTimeParseException e) {
            return JSON.toJSONString(new ConflictReport() {{
                setHasConflict(false);
                setSummary("日期解析失败：" + e.getMessage());
            }});
        }
        if (depDate.isAfter(retDate)) {
            return JSON.toJSONString(new ConflictReport() {{
                setHasConflict(false);
                setSummary("出发日期（" + departureDate + "）晚于返回日期（" + returnDate + "），请先修正日期。");
            }});
        }

        // ── 查询已有生效中差旅单（日期有重叠） ─────────────────────────────
        Set<TravelOrderStatus> activeStatuses = Set.of(
                TravelOrderStatus.DRAFT, TravelOrderStatus.SUBMITTED, TravelOrderStatus.APPROVED);
        List<TravelOrder> candidates = travelOrderRepository.findActiveByUserIdAndDateRange(
                userId, activeStatuses, departureDate, returnDate);

        // 排除自身（修改场景）
        if (!isBlank(excludeOrderId)) {
            candidates.removeIf(o -> excludeOrderId.equals(o.getOrderId()));
        }

        // ── 冲突检测 ────────────────────────────────────────────────────────
        List<ConflictItem> conflicts = new ArrayList<>();
        for (TravelOrder existing : candidates) {
            if (!isValidDate(existing.getDepartureDate()) || !isValidDate(existing.getReturnDate())) {
                // 数据异常，跳过但记录日志
                logger.warn("[TOOL][check_travel_order_conflicts] 跳过数据异常的差旅单 orderId={}", existing.getOrderId());
                continue;
            }
            detectConflictsFor(existing, departureCity, destination, depDate, retDate, conflicts);
        }

        // 按严重等级降序：HIGH > MEDIUM > LOW
        conflicts.sort(Comparator.comparingInt((ConflictItem c) -> severityRank(c.getSeverity()))
                .thenComparing(c -> c.getOrderId() == null ? "" : c.getOrderId()));

        ConflictReport report = new ConflictReport();
        report.setConflicts(conflicts);
        report.setTotalConflicts(conflicts.size());
        report.setHasConflict(!conflicts.isEmpty());
        report.setSummary(buildSummary(conflicts, departureCity, destination, departureDate, returnDate));

        logger.info("[TOOL][check_travel_order_conflicts] userId={} 命中冲突 {} 条", userId, conflicts.size());
        return JSON.toJSONString(report);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 单条已有差旅单 vs 候选差旅单的冲突检测
    // ─────────────────────────────────────────────────────────────────────────

    private void detectConflictsFor(TravelOrder existing,
                                    String candidateDepCity,
                                    String candidateDest,
                                    LocalDate candidateDep,
                                    LocalDate candidateRet,
                                    List<ConflictItem> out) {
        LocalDate existingDep = LocalDate.parse(existing.getDepartureDate());
        LocalDate existingRet = LocalDate.parse(existing.getReturnDate());
        String existingDepCity = existing.getDepartureCity();
        String existingDest = existing.getDestination();

        // ① 日期完全重叠：[existingDep, existingRet] ∩ [candidateDep, candidateRet] 非空
        if (!existingRet.isBefore(candidateDep) && !existingDep.isAfter(candidateRet)) {
            addOverlapConflict(existing, existingDep, existingRet, existingDepCity, existingDest,
                    candidateDep, candidateRet, candidateDepCity, candidateDest, out);
            // 已有重叠则不需再判断衔接
            return;
        }

        // ② 同日衔接：existingRet == candidateDep（同一天往返两个城市）
        if (existingRet.isEqual(candidateDep)) {
            addSameDayTransitConflict(existing, existingDepCity, existingDest,
                    candidateDepCity, candidateDest, out);
            return;
        }

        // ③ 次日衔接：existingRet == candidateDep - 1 天（昨天结束、今天出发）
        if (existingRet.plusDays(1).isEqual(candidateDep)) {
            addAdjacentDayConflict(existing, "departure", existingDep, existingRet, existingDepCity, existingDest,
                    candidateDep, candidateRet, candidateDepCity, candidateDest, out);
            return;
        }

        // ④ 反向同日衔接：existingDep == candidateRet
        if (existingDep.isEqual(candidateRet)) {
            addSameDayTransitConflict(existing, existingDepCity, existingDest,
                    candidateDepCity, candidateDest, out);
            return;
        }

        // ⑤ 反向次日衔接：existingDep == candidateRet + 1 天
        if (existingDep.minusDays(1).isEqual(candidateRet)) {
            addAdjacentDayConflict(existing, "return", existingDep, existingRet, existingDepCity, existingDest,
                    candidateDep, candidateRet, candidateDepCity, candidateDest, out);
        }
    }

    /**
     * 添加"日期完全重叠"冲突。
     * <p>同城市=LOW 重复提交；不同城市=HIGH 物理不可能。</p>
     */
    private void addOverlapConflict(TravelOrder existing,
                                    LocalDate existingDep, LocalDate existingRet,
                                    String existingDepCity, String existingDest,
                                    LocalDate candidateDep, LocalDate candidateRet,
                                    String candidateDepCity, String candidateDest,
                                    List<ConflictItem> out) {
        boolean sameCity = isSameCity(existingDepCity, existingDest, candidateDepCity, candidateDest);
        ConflictItem item = new ConflictItem();
        item.setOrderId(existing.getOrderId());
        item.setOrderSummary(buildOrderSummary(existing));
        if (sameCity) {
            item.setType(ConflictItem.TYPE_TIME_OVERLAP_SAME_CITY);
            item.setSeverity(ConflictItem.SEVERITY_LOW);
            item.setDescription(String.format(
                    "已有差旅单的时间段（%s ~ %s，%s ↔ %s）与本次完全重叠，属于同城市重复提交。",
                    existingDep, existingRet, existingDepCity, existingDest));
            item.setSuggestion("可继续提交，但建议先取消或调整其中一张差旅单，避免重复审批。");
        } else {
            item.setType(ConflictItem.TYPE_TIME_OVERLAP_DIFF_CITY);
            item.setSeverity(ConflictItem.SEVERITY_HIGH);
            item.setDescription(String.format(
                    "已有差旅单（%s ~ %s，%s ↔ %s）与本次（%s ~ %s，%s → %s）在时间段上重叠，"
                            + "但目的地不同，物理上不可能同时身处两地。",
                    existingDep, existingRet, existingDepCity, existingDest,
                    candidateDep, candidateRet, candidateDepCity, candidateDest));
            item.setSuggestion("请调整本次差旅的出发日期或返回日期，使其与已有差旅单不重叠；"
                    + "如确有需要请先取消/修改已有差旅单。");
        }
        out.add(item);
    }

    /**
     * 添加"同日衔接"冲突——同一天需要完成从 existingDest 到 candidateDepCity 的跨城交通。
     */
    private void addSameDayTransitConflict(TravelOrder existing,
                                           String existingDepCity, String existingDest,
                                           String candidateDepCity, String candidateDest,
                                           List<ConflictItem> out) {
        int minutes = cityTransitTimeService.estimateMinutes(existingDest, candidateDepCity);
        ConflictItem item = new ConflictItem();
        item.setOrderId(existing.getOrderId());
        item.setOrderSummary(buildOrderSummary(existing));
        item.setType(ConflictItem.TYPE_TRANSIT_TOO_TIGHT);
        if (minutes <= 0) {
            // 同城，无冲突（不会到这里，但保险）
            return;
        }
        if (minutes > SAME_DAY_IMPOSSIBLE_MINUTES) {
            item.setSeverity(ConflictItem.SEVERITY_HIGH);
            item.setDescription(String.format(
                    "同日需要从 %s 前往 %s，最短衔接时间约 %d 小时，超出当日可行范围，物理上无法完成。",
                    existingDest, candidateDepCity, minutes / 60));
            item.setSuggestion("请将出发日期至少延后 1 天，或调整目的地。");
        } else if (minutes > SAME_DAY_TIGHT_MINUTES) {
            item.setSeverity(ConflictItem.SEVERITY_MEDIUM);
            item.setDescription(String.format(
                    "同日需要从 %s 前往 %s，估算最短衔接时间约 %d 小时，时间非常紧张。",
                    existingDest, candidateDepCity, minutes / 60));
            item.setSuggestion("建议改为次日出发，或选择更早的航班/高铁以预留缓冲时间。");
        } else {
            item.setSeverity(ConflictItem.SEVERITY_MEDIUM);
            item.setDescription(String.format(
                    "同日需要从 %s 前往 %s，估算衔接时间约 %d 小时。",
                    existingDest, candidateDepCity, minutes / 60));
            item.setSuggestion("衔接可行但偏紧，建议选择早班交通，并预留 1~2 小时缓冲。");
        }
        out.add(item);
    }

    /**
     * 添加"次日衔接 / 路径断裂"冲突。
     *
     * @param side "departure" — 候选出发前一天结束；"return" — 候选返回后第一天开始
     */
    private void addAdjacentDayConflict(TravelOrder existing,
                                        String side,
                                        LocalDate existingDep, LocalDate existingRet,
                                        String existingDepCity, String existingDest,
                                        LocalDate candidateDep, LocalDate candidateRet,
                                        String candidateDepCity, String candidateDest,
                                        List<ConflictItem> out) {
        // 仅检测跨城衔接是否合理：已有差旅的"结束城市"必须 = 候选差旅的"出发城市"
        String endCity;
        String startCity;
        String relation;
        if ("departure".equals(side)) {
            endCity = existingDest;
            startCity = candidateDepCity;
            relation = String.format("已有差旅在 %s 结束于 %s，候选差旅在次日（%s）从 %s 出发",
                    existingRet, existingDest, candidateDep, candidateDepCity);
        } else {
            endCity = candidateDest;
            startCity = existingDepCity;
            relation = String.format("候选差旅在 %s 结束于 %s，已有差旅在次日（%s）从 %s 出发",
                    candidateRet, candidateDest, existingDep, existingDepCity);
        }

        if (isSameCityValue(endCity, startCity)) {
            // 城市一致，无需判断
            return;
        }

        int minutes = cityTransitTimeService.estimateMinutes(endCity, startCity);
        if (minutes <= SAME_DAY_TIGHT_MINUTES) {
            // 1 天时间够跨城衔接（>= 8h 都认为够）
            return;
        }

        ConflictItem item = new ConflictItem();
        item.setOrderId(existing.getOrderId());
        item.setOrderSummary(buildOrderSummary(existing));
        item.setType(ConflictItem.TYPE_DISCONNECTED_ROUTE);
        item.setSeverity(ConflictItem.SEVERITY_MEDIUM);
        item.setDescription(String.format(
                "%s，但跨城交通至少需要 %d 小时，仅 1 天时间衔接偏紧，"
                        + "存在误机/赶不上高铁的风险。",
                relation, minutes / 60));
        item.setSuggestion("建议在两段行程之间留出 1~2 天缓冲，或将其中一段改为同城行程。");
        out.add(item);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 工具方法
    // ─────────────────────────────────────────────────────────────────────────

    private static int severityRank(String severity) {
        if (ConflictItem.SEVERITY_HIGH.equals(severity)) {
            return 0;
        }
        if (ConflictItem.SEVERITY_MEDIUM.equals(severity)) {
            return 1;
        }
        if (ConflictItem.SEVERITY_LOW.equals(severity)) {
            return 2;
        }
        return 3;
    }

    private static String buildOrderSummary(TravelOrder o) {
        return String.format("%s → %s，%s ~ %s（%s）",
                safe(o.getDepartureCity()),
                safe(o.getDestination()),
                safe(o.getDepartureDate()),
                safe(o.getReturnDate()),
                o.getStatus() == null ? "-" : o.getStatus().getCode());
    }

    private static String buildSummary(List<ConflictItem> conflicts,
                                       String depCity, String dest, String depDate, String retDate) {
        if (conflicts.isEmpty()) {
            return String.format("已检查用户已有差旅单，未发现与 %s → %s（%s ~ %s）存在冲突。",
                    depCity, dest, depDate, retDate);
        }
        long high = conflicts.stream().filter(c -> ConflictItem.SEVERITY_HIGH.equals(c.getSeverity())).count();
        long medium = conflicts.stream().filter(c -> ConflictItem.SEVERITY_MEDIUM.equals(c.getSeverity())).count();
        long low = conflicts.stream().filter(c -> ConflictItem.SEVERITY_LOW.equals(c.getSeverity())).count();
        return String.format("命中 %d 条冲突（HIGH=%d, MEDIUM=%d, LOW=%d），请逐条阅读 description 与 suggestion。",
                conflicts.size(), high, medium, low);
    }

    private static boolean isSameCity(String depCityA, String destA, String depCityB, String destB) {
        // 视作"同城市重复"：出发地与目的地都成对相同（双向一致）
        return isSameCityValue(depCityA, depCityB) && isSameCityValue(destA, destB);
    }

    private static boolean isSameCityValue(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        String na = a.trim();
        String nb = b.trim();
        if (na.endsWith("市") && na.length() > 1) {
            na = na.substring(0, na.length() - 1);
        }
        if (nb.endsWith("市") && nb.length() > 1) {
            nb = nb.substring(0, nb.length() - 1);
        }
        return na.equalsIgnoreCase(nb);
    }

    private static boolean isValidDate(String s) {
        if (s == null || s.isBlank()) {
            return false;
        }
        try {
            LocalDate.parse(s);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    /** 计算日期差（天数） */
    @SuppressWarnings("unused")
    private static long daysBetween(LocalDate a, LocalDate b) {
        return ChronoUnit.DAYS.between(a, b);
    }
}
