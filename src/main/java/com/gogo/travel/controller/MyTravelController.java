package com.gogo.travel.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.gogo.travel.business.approval.entity.ApprovalRecord;
import com.gogo.travel.business.approval.service.ApprovalService;
import com.gogo.travel.business.booking.entity.BookingRecord;
import com.gogo.travel.business.booking.repo.BookingRecordRepository;
import com.gogo.travel.business.order.entity.TravelOrder;
import com.gogo.travel.business.order.entity.TravelOrderStatus;
import com.gogo.travel.business.order.repo.TravelOrderRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 普通用户「我的差旅」入口：聚合展示当前登录用户的出差申请、审批状态与预订记录。
 *
 * <p>与后台管理员的 {@link AdminApprovalController} 不同，本接口不做角色校验，
 * 仅按 {@code StpUtil.getLoginIdAsString()} 隔离数据租户。</p>
 *
 * @author Hollis
 */
@RestController
@RequestMapping("/api/my-travel")
@CrossOrigin(origins = "*")
public class MyTravelController {

    private static final Logger logger = LoggerFactory.getLogger(MyTravelController.class);

    /**
     * 用中文书写的常见境外目的地（含国家 / 地区 / 城市），命中即视为国际差旅。不是所有城市和国家都包含，因为出差一般是去有业务往来的地区。
     * <p>说明：香港 / 澳门 / 台湾按简化口径视为境内，不在此列表内。</p>
     */
    private static final Set<String> INTERNATIONAL_KEYWORDS = Set.of(
            "日本", "东京", "大阪", "京都", "名古屋", "北海道", "冲绳",
            "韩国", "首尔", "釜山", "济州",
            "美国", "纽约", "洛杉矶", "旧金山", "西雅图", "华盛顿", "芝加哥", "波士顿", "拉斯维加斯", "夏威夷",
            "加拿大", "多伦多", "温哥华", "蒙特利尔",
            "英国", "伦敦", "曼彻斯特", "爱丁堡",
            "法国", "巴黎", "尼斯", "马赛",
            "德国", "柏林", "慕尼黑", "法兰克福", "汉堡",
            "意大利", "罗马", "米兰", "威尼斯", "佛罗伦萨",
            "西班牙", "马德里", "巴塞罗那",
            "葡萄牙", "里斯本",
            "荷兰", "阿姆斯特丹",
            "比利时", "布鲁塞尔",
            "瑞士", "苏黎世", "日内瓦", "伯尔尼",
            "瑞典", "斯德哥尔摩",
            "挪威", "奥斯陆",
            "丹麦", "哥本哈根",
            "芬兰", "赫尔辛基",
            "俄罗斯", "莫斯科", "圣彼得堡",
            "澳大利亚", "悉尼", "墨尔本", "布里斯班", "珀斯",
            "新西兰", "奥克兰", "惠灵顿",
            "新加坡",
            "马来西亚", "吉隆坡", "槟城",
            "泰国", "曼谷", "普吉", "清迈", "芭提雅",
            "越南", "河内", "胡志明", "岘港",
            "菲律宾", "马尼拉", "宿务",
            "印度尼西亚", "雅加达", "巴厘岛",
            "印度", "新德里", "孟买", "班加罗尔",
            "阿联酋", "迪拜", "阿布扎比",
            "沙特", "利雅得",
            "土耳其", "伊斯坦布尔",
            "以色列", "特拉维夫",
            "南非", "开普敦", "约翰内斯堡",
            "埃及", "开罗",
            "巴西", "里约", "圣保罗",
            "阿根廷", "布宜诺斯艾利斯",
            "墨西哥", "墨西哥城");

    @Autowired
    private TravelOrderRepository travelOrderRepository;
    @Autowired
    private ApprovalService approvalService;
    @Autowired
    private BookingRecordRepository bookingRecordRepository;

    /**
     * 查询当前登录用户的全部出差申请，含每单最新审批状态与预订摘要。
     * <p>按创建时间倒序返回，方便前端顶部优先展示最新申请。</p>
     */
    @GetMapping("/orders")
    @SaCheckLogin
    public List<Map<String, Object>> listMyOrders() {
        String userId = StpUtil.getLoginIdAsString();
        List<TravelOrder> orders = travelOrderRepository.findByUserId(userId);
        // 按创建时间倒序（null 视为最早）
        orders.sort(Comparator.comparing(TravelOrder::getCreatedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));

        List<Map<String, Object>> result = new ArrayList<>(orders.size());
        for (TravelOrder order : orders) {
            result.add(toView(userId, order));
        }
        logger.info("[MyTravelController] userId={} 返回 {} 条差旅单", userId, result.size());
        return result;
    }

    /** 将差旅单 + 关联审批 + 关联预订记录聚合为前端视图 */
    private Map<String, Object> toView(String userId, TravelOrder order) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("orderId", order.getOrderId());
        view.put("destination", order.getDestination());
        view.put("departureCity", order.getDepartureCity());
        view.put("departureDate", order.getDepartureDate());
        view.put("returnDate", order.getReturnDate());
        view.put("purpose", order.getPurpose());
        TravelOrderStatus status = order.getStatus();
        view.put("status", status != null ? status.getCode() : null);
        view.put("statusLabel", status != null ? status.getLabel() : null);
        view.put("createdAt", toMillis(order.getCreatedAt()));
        view.put("updatedAt", toMillis(order.getUpdatedAt()));
        view.put("international", isInternational(order.getDestination()));

        // 审批信息（取该差旅单最新一条审批记录）
        Optional<ApprovalRecord> approvalOpt = approvalService.findLatestByOrderId(order.getOrderId());
        if (approvalOpt.isPresent()) {
            ApprovalRecord ar = approvalOpt.get();
            view.put("approvalId", ar.getProcessInstanceId());
            view.put("approvalStatus", ar.getStatus() != null ? ar.getStatus().getCode() : null);
            view.put("approvalStatusLabel", ar.getStatus() != null ? ar.getStatus().getLabel() : null);
            view.put("approvalRemark", ar.getRemark());
            view.put("approvalSubmitTime", toMillis(ar.getSubmitTime()));
            view.put("approvalUpdateTime", toMillis(ar.getUpdateTime()));
        } else {
            view.put("approvalId", null);
            view.put("approvalStatus", null);
            view.put("approvalStatusLabel", null);
            view.put("approvalRemark", null);
            view.put("approvalSubmitTime", null);
            view.put("approvalUpdateTime", null);
        }

        // 预订记录
        List<BookingRecord> bookings = bookingRecordRepository.findByUserIdAndTravelOrderId(userId, order.getOrderId());
        List<Map<String, Object>> bookingViews = new ArrayList<>(bookings.size());
        for (BookingRecord br : bookings) {
            bookingViews.add(toBookingView(br));
        }
        view.put("bookings", bookingViews);
        view.put("bookingCount", bookingViews.size());
        return view;
    }

    private Map<String, Object> toBookingView(BookingRecord br) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("bookingId", br.getBookingId());
        v.put("bizType", br.getBizType() != null ? br.getBizType().getCode() : null);
        v.put("bizTypeLabel", br.getBizType() != null ? br.getBizType().getLabel() : null);
        v.put("title", br.getTitle());
        v.put("status", br.getStatus() != null ? br.getStatus().getCode() : null);
        v.put("statusLabel", br.getStatus() != null ? br.getStatus().getLabel() : null);
        v.put("externalStatus", br.getExternalStatus());
        BigDecimal amount = br.getTotalAmount();
        v.put("totalAmount", amount != null ? amount.toPlainString() : null);
        v.put("currency", br.getCurrency());
        v.put("platform", br.getPlatform());
        v.put("externalOrderNo", br.getExternalOrderNo());
        v.put("paymentStatus", br.getPaymentStatus());
        v.put("startTime", toMillis(br.getStartTime()));
        v.put("endTime", toMillis(br.getEndTime()));
        v.put("bookedAt", toMillis(br.getBookedAt()));
        return v;
    }

    private Long toMillis(Instant t) {
        return t != null ? t.toEpochMilli() : null;
    }

    /**
     * 简单判断目的地是否属于国际差旅：
     * <ol>
     *   <li>为空或纯 CJK 汉字且未命中境外关键词 → 视为境内；</li>
     *   <li>包含任何非 CJK 字符（英文名 / 拼音等） → 视为境外；</li>
     *   <li>命中 {@link #INTERNATIONAL_KEYWORDS} 中的国家/城市 → 视为境外。</li>
     * </ol>
     */
    boolean isInternational(String destination) {
        if (destination == null || destination.isBlank()) {
            return false;
        }
        String trimmed = destination.trim();
        // 命中境外关键词（子串匹配，兼容"日本东京"这类组合）
        for (String kw : INTERNATIONAL_KEYWORDS) {
            if (trimmed.contains(kw)) {
                return true;
            }
        }
        // 含非 CJK 字符（拉丁字母等）视为境外目的地
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (Character.isLetter(c) && !isCjk(c)) {
                return true;
            }
        }
        return false;
    }

    private boolean isCjk(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION;
    }
}
