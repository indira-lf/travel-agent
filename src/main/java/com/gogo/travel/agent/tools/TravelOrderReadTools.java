package com.gogo.travel.agent.tools;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gogo.travel.agent.context.AgentSessionContext;
import com.gogo.travel.business.order.entity.TravelOrder;
import com.gogo.travel.business.order.entity.TravelOrderStatus;
import com.gogo.travel.business.order.mapper.TravelOrderMapper;
import com.gogo.travel.business.order.repo.TravelOrderRepository;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * @author Hollis
 */
@Component
public class TravelOrderReadTools {

    private static final Logger logger = LoggerFactory.getLogger(TravelOrderReadTools.class);

    @Autowired
    private TravelOrderRepository travelOrderRepository;

    @Autowired
    private TravelOrderMapper travelOrderMapper;

    @Tool(name = "query_travel_order", description = "查询用户差旅单列表或指定差旅单详情。支持按状态、出发日期范围过滤。")
    public String queryTravelOrder(
            AgentSessionContext sessionCtx,
            @ToolParam(name = "order_id", description = "差旅单ID，传入则直接返回该单详情，忽略其他筛选条件", required = false) String orderId,
            @ToolParam(name = "status", description = "状态过滤，支持单个或逗号分隔的多个状态，如 DRAFT 或 DRAFT,SUBMITTED。可选值: DRAFT/SUBMITTED/APPROVED/REJECTED/COMPLETED/CANCELLED", required = false) String status,
            @ToolParam(name = "start_date", description = "筛选出发日期不早于此日期的差旅单，格式 YYYY-MM-DD，可选", required = false) String startDate,
            @ToolParam(name = "end_date", description = "筛选出发日期不晚于此日期的差旅单，格式 YYYY-MM-DD，可选", required = false) String endDate) {

        // 从会话上下文中获取用户ID ，能确保不出错
        String userId = sessionCtx.getUserId();
        logger.info("[TOOL][query_travel_order] userId={}, orderId={}, status={}, startDate={}, endDate={}",
                userId, orderId, status, startDate, endDate);

        if (orderId != null && !orderId.isBlank()) {
            TravelOrder order = travelOrderRepository.findByOrderId(orderId).orElse(null);
            // 将当前操作的差旅单ID记录到会话上下文，供 BookingPersistenceHook 关联预订记录
            if (order != null) {
                sessionCtx.setTravelOrderId(orderId);
            }
            String result = JSON.toJSONString(order != null ? order : "{\"error\":\"order not found\"}");
            logger.info("[TOOL][query_travel_order] result={}", result);
            return result;
        }

        List<TravelOrder> orders;
        boolean hasDateFilter = (startDate != null && !startDate.isBlank())
                                || (endDate != null && !endDate.isBlank());

        if (hasDateFilter) {
            // 日期范围查询：按出发日期 >= startDate AND 出发日期 <= endDate 过滤
            LambdaQueryWrapper<TravelOrder> wrapper = new LambdaQueryWrapper<TravelOrder>()
                    .eq(TravelOrder::getUserId, userId);
            if (status != null && !status.isBlank()) {
                List<TravelOrderStatus> statusList = parseStatusList(status);
                if (statusList == null) {
                    return JSON.toJSONString(java.util.Map.of("error",
                            "无效的状态参数: " + status + "，可选值: DRAFT/SUBMITTED/APPROVED/REJECTED/COMPLETED/CANCELLED"));
                }
                if (statusList.size() == 1) {
                    wrapper.eq(TravelOrder::getStatus, statusList.get(0));
                } else {
                    wrapper.in(TravelOrder::getStatus, statusList);
                }
            }
            if (startDate != null && !startDate.isBlank()) {
                wrapper.ge(TravelOrder::getDepartureDate, startDate);
            }
            if (endDate != null && !endDate.isBlank()) {
                wrapper.le(TravelOrder::getDepartureDate, endDate);
            }
            wrapper.orderByAsc(TravelOrder::getDepartureDate);
            orders = travelOrderMapper.selectList(wrapper);
        } else if (status != null && !status.isBlank()) {
            List<TravelOrderStatus> statusList = parseStatusList(status);
            if (statusList == null) {
                return JSON.toJSONString(java.util.Map.of("error",
                        "无效的状态参数: " + status + "，可选值: DRAFT/SUBMITTED/APPROVED/REJECTED/COMPLETED/CANCELLED"));
            }
            if (statusList.size() == 1) {
                orders = travelOrderRepository.findByUserIdAndStatus(userId, statusList.get(0));
            } else {
                LambdaQueryWrapper<TravelOrder> wrapper = new LambdaQueryWrapper<TravelOrder>()
                        .eq(TravelOrder::getUserId, userId)
                        .in(TravelOrder::getStatus, statusList)
                        .orderByAsc(TravelOrder::getDepartureDate);
                orders = travelOrderMapper.selectList(wrapper);
            }
        } else {
            orders = travelOrderRepository.findByUserId(userId);
        }

        String result = JSON.toJSONString(orders);
        logger.info("[TOOL][query_travel_order] result={}", result);
        return result;
    }

    /**
     * 解析状态参数，支持单个状态或逗号分隔的多个状态。
     * 返回 null 表示存在无效状态值。
     */
    private List<TravelOrderStatus> parseStatusList(String status) {
        String[] parts = status.split(",");
        List<TravelOrderStatus> list = new ArrayList<>(parts.length);
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            TravelOrderStatus s = TravelOrderStatus.of(trimmed);
            if (s == null) {
                return null;
            }
            list.add(s);
        }
        return list.isEmpty() ? null : list;
    }
}
