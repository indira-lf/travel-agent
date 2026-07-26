package com.gogo.travel.agent.tools;

import com.alibaba.fastjson2.JSON;
import com.gogo.travel.agent.context.AgentSessionContext;
import com.gogo.travel.business.approval.entity.ApprovalRecord;
import com.gogo.travel.business.approval.entity.ApprovalRecordStatus;
import com.gogo.travel.business.approval.service.ApprovalService;
import com.gogo.travel.business.booking.entity.BookingRecord;
import com.gogo.travel.business.booking.entity.BookingStatus;
import com.gogo.travel.business.booking.repo.BookingRecordRepository;
import com.gogo.travel.business.order.entity.TravelOrder;
import com.gogo.travel.business.order.entity.TravelOrderStatus;
import com.gogo.travel.business.order.repo.TravelOrderRepository;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 差旅单全生命周期工具集：提交审批、查询审批状态、取消出差申请、修改出差申请。
 * 所有操作共享 TravelOrderRepository 和 ApprovalService 依赖。
 *
 * @author Hollis
 */
@Component
public class TravelOrderWriteTools {

    private static final Logger logger = LoggerFactory.getLogger(TravelOrderWriteTools.class);
    /**
     * 日期格式校验：YYYY-MM-DD
     */
    private static final Pattern DATE_PATTERN = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    @Autowired
    private TravelOrderRepository travelOrderRepository;
    @Autowired
    private ApprovalService approvalService;
    @Autowired
    private BookingRecordRepository bookingRecordRepository;

    // ─────────────────────────────────────────────────────────────────────────
    // 强类型返回结构（Java Record）
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 通用失败响应
     */
    record ErrorResult(boolean success, String errorCode, String message) {
        static ErrorResult of(String errorCode, String message) {
            return new ErrorResult(false, errorCode, message);
        }
    }

    /**
     * 需用户二次确认的拦截响应
     */
    record ConfirmRequiredResult(boolean success, boolean needConfirm, String orderId, String currentStatus,
                                 String message) {
    }

    /**
     * submit_travel_approval 成功响应
     */
    record SubmitResult(boolean success, String orderId, String processInstanceId, String orderStatus,
                        String approvalStatus, String submitTime, String destination, String departureCity,
                        String departureDate, String returnDate, String purpose, String message) {
    }

    /**
     * query_approval_status 响应（found=false 时业务字段为 null）
     */
    record QueryApprovalResult(boolean found, String queryMode, String processInstanceId, String orderId, String userId,
                               String title, String status, String submitTime, String updateTime, String remark,
                               String message) {
    }

    /**
     * 关联预订摘要（用于在取消/修改结果中告知 LLM 受影响的预订）
     */
    record BookingSummary(String bookingId, String bizType, String title, String status, String externalOrderNo) {
    }

    /**
     * cancel_travel_order 成功响应
     */
    record CancelResult(boolean success, String orderId, String orderStatus, boolean approvalCancelled,
                        String cancelledApprovalId, String reason, String message,
                        List<BookingSummary> associatedBookings, String affectedBookingsMessage) {
    }

    /**
     * modify_travel_order 成功响应
     */
    record ModifyResult(boolean success, String orderId, String orderStatus, String oldApprovalId,
                        boolean oldApprovalCancelled, String newApprovalId, String newApprovalStatus,
                        String updatedFields, String message,
                        List<BookingSummary> associatedBookings, String affectedBookingsMessage) {
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 提交审批（同时创建差旅单 + 审批单，双向关联）
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(name = "submit_travel_approval", description = "一次性创建差旅申请单（差旅单）和审批单，并将两者双向关联。" + "执行步骤：① 根据出行信息生成差旅单（DRAFT）；" + "② 提交审批单（PENDING）并绑定差旅单ID；" + "③ 将差旅单状态更新为 SUBMITTED 并写入审批单ID。" + "返回差旅单ID（orderId）和审批单ID（processInstanceId）。" + "日期字段必须为 YYYY-MM-DD 格式（例如 2026-07-15），否则会返回参数错误。")
    public String submitTravelApproval(AgentSessionContext sessionCtx, @ToolParam(name = "destination", description = "目的地城市") String destination, @ToolParam(name = "departure_city", description = "出发城市") String departureCity, @ToolParam(name = "departure_date", description = "出发日期，必须为 YYYY-MM-DD 格式，例如 2026-07-15") String departureDate, @ToolParam(name = "return_date", description = "返回日期，必须为 YYYY-MM-DD 格式，例如 2026-07-18") String returnDate, @ToolParam(name = "purpose", description = "出差事由") String purpose) {

        String userId = sessionCtx.getUserId();
        logger.info("[TOOL][submit_travel_approval] userId={}, destination={}, departureDate={}, returnDate={}", userId, destination, departureDate, returnDate);

        // ── 入参校验 ──────────────────────────────────────────────────────────
        List<String> errors = new ArrayList<>();
        if (isBlank(userId)) {
            errors.add("user_id 不能为空");
        }
        if (isBlank(destination)) {
            errors.add("destination（目的地城市）不能为空");
        }
        if (isBlank(departureCity)) {
            errors.add("departure_city（出发城市）不能为空");
        }
        if (isBlank(purpose)) {
            errors.add("purpose（出差事由）不能为空");
        }

        String departureDateErr = validateDate(departureDate, "departure_date（出发日期）");
        if (departureDateErr != null) {
            errors.add(departureDateErr);
        }

        String returnDateErr = validateDate(returnDate, "return_date（返回日期）");
        if (returnDateErr != null) {
            errors.add(returnDateErr);
        }

        if (!errors.isEmpty()) {
            return toJson(ErrorResult.of("INVALID_PARAM", String.join("；", errors)));
        }

        // 出发日期不能晚于返回日期
        if (LocalDate.parse(departureDate).isAfter(LocalDate.parse(returnDate))) {
            return toJson(ErrorResult.of("INVALID_DATE_RANGE", "出发日期（" + departureDate + "）不能晚于返回日期（" + returnDate + "）"));
        }

        // ── 业务逻辑 ──────────────────────────────────────────────────────────
        try {
            // ⓪ 幂等校验：相同用户 + 出行要素若已存在生效中的差旅单，直接返回已有单，避免重复创建
            TravelOrder duplicate = findDuplicateActiveOrder(userId, destination, departureCity, departureDate, returnDate, purpose);
            if (duplicate != null) {
                logger.info("[TOOL][submit_travel_approval] 命中幂等：已存在生效中的差旅单 orderId={}，跳过重复创建", duplicate.getOrderId());
                sessionCtx.setTravelOrderId(duplicate.getOrderId());
                return buildExistingSubmitResult(duplicate);
            }

            // ① 创建差旅单（DRAFT）
            TravelOrder order = new TravelOrder();
            order.setUserId(userId);
            order.setDestination(destination);
            order.setDepartureCity(departureCity);
            order.setDepartureDate(departureDate);
            order.setReturnDate(returnDate);
            order.setPurpose(purpose);
            order.setStatus(TravelOrderStatus.DRAFT);
            TravelOrder savedOrder = travelOrderRepository.save(order);
            String orderId = savedOrder.getOrderId();
            sessionCtx.setTravelOrderId(orderId);
            logger.info("[TOOL][submit_travel_approval] ① 差旅单已创建 orderId={}", orderId);

            // ② 构建审批表单并提交审批单
            String approvalForm = buildApprovalForm(orderId, userId, purpose, destination, departureCity, departureDate, returnDate);
            ApprovalRecord record = approvalService.submit(userId, purpose, approvalForm, orderId);
            String processInstanceId = record.getProcessInstanceId();
            logger.info("[TOOL][submit_travel_approval] ② 审批单已提交 processInstanceId={}", processInstanceId);

            // ③ 差旅单写入审批单ID，状态升级为 SUBMITTED
            savedOrder.setApprovalId(processInstanceId);
            savedOrder.setStatus(TravelOrderStatus.SUBMITTED);
            travelOrderRepository.save(savedOrder);
            logger.info("[TOOL][submit_travel_approval] ③ 双向关联完成：差旅单 {} ↔ 审批单 {}", orderId, processInstanceId);

            // ④ 查库验证写入成功
            String submitVerifyErr = verifyOrderStatus(orderId, TravelOrderStatus.SUBMITTED, "submit_travel_approval");
            if (submitVerifyErr != null) {
                return submitVerifyErr;
            }

            return toJson(new SubmitResult(true, orderId, processInstanceId, TravelOrderStatus.SUBMITTED.getCode(), record.getStatus().getCode(), record.getSubmitTime().toString(), destination, departureCity, departureDate, returnDate, purpose, String.format("差旅申请已创建并提交审批。差旅单ID: %s，审批单ID: %s，当前状态: 待审批。", orderId, processInstanceId)));

        } catch (Exception e) {
            logger.error("[TOOL][submit_travel_approval] 操作失败 userId={}", userId, e);
            return toJson(ErrorResult.of("INTERNAL_ERROR", "创建差旅申请失败：" + e.getMessage()));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 查询审批状态
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(name = "query_approval_status", description = "查询差旅审批状态。若提供审批实例ID则按ID查询；否则按用户ID查询其最近的审批单")
    public String queryApprovalStatus(AgentSessionContext sessionCtx, @ToolParam(name = "process_instance_id", description = "审批实例ID，可选", required = false) String processInstanceId) {

        String userId = sessionCtx.getUserId();
        logger.info("[TOOL][query_approval_status] userId={}, processInstanceId={}", userId, processInstanceId);

        if (isBlank(userId)) {
            return toJson(ErrorResult.of("INVALID_PARAM", "user_id 不能为空"));
        }

        try {
            Optional<ApprovalRecord> recordOpt;
            String queryMode;
            if (!isBlank(processInstanceId)) {
                recordOpt = approvalService.findByProcessInstanceId(processInstanceId);
                queryMode = "by_instance";
            } else {
                recordOpt = approvalService.findLatestByUserId(userId);
                queryMode = "latest";
            }

            if (recordOpt.isEmpty()) {
                return toJson(new QueryApprovalResult(false, queryMode, null, null, null, null, null, null, null, null, "未找到相关审批记录。"));
            }

            ApprovalRecord record = recordOpt.get();
            logger.info("[TOOL][query_approval_status] 查询到审批单 processInstanceId={}, status={}", record.getProcessInstanceId(), record.getStatus());

            return toJson(new QueryApprovalResult(true, queryMode, record.getProcessInstanceId(), record.getOrderId(), record.getUserId(), record.getTitle(), record.getStatus().getCode(), record.getSubmitTime() != null ? record.getSubmitTime().toString() : null, record.getUpdateTime() != null ? record.getUpdateTime().toString() : null, record.getRemark(), null));

        } catch (Exception e) {
            logger.error("[TOOL][query_approval_status] 查询失败 userId={}", userId, e);
            return toJson(ErrorResult.of("INTERNAL_ERROR", "查询审批状态失败：" + e.getMessage()));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 取消出差申请
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(name = "cancel_travel_order", description = "取消出差申请：将差旅单状态设为 CANCELLED，同时撤销关联的审批单（若存在）。" + "仅 DRAFT/SUBMITTED 状态可直接取消；APPROVED 状态会给出警告并要求用户二次确认（force=true）。")
    public String cancelTravelOrder(AgentSessionContext sessionCtx, @ToolParam(name = "order_id", description = "要取消的差旅单ID") String orderId, @ToolParam(name = "reason", description = "取消原因，可选", required = false) String reason, @ToolParam(name = "force", description = "是否强制取消已审批的差旅单，默认 false", required = false) Boolean force) {

        String userId = sessionCtx.getUserId();
        logger.info("[TOOL][cancel_travel_order] userId={}, orderId={}, force={}", userId, orderId, force);

        if (isBlank(userId)) {
            return toJson(ErrorResult.of("INVALID_PARAM", "user_id 不能为空"));
        }
        if (isBlank(orderId)) {
            return toJson(ErrorResult.of("INVALID_PARAM", "order_id 不能为空"));
        }

        try {
            Optional<TravelOrder> orderOpt = travelOrderRepository.findByOrderId(orderId);
            if (orderOpt.isEmpty()) {
                return toJson(ErrorResult.of("ORDER_NOT_FOUND", "差旅单不存在：" + orderId));
            }

            TravelOrder order = orderOpt.get();
            if (!userId.equals(order.getUserId())) {
                return toJson(ErrorResult.of("PERMISSION_DENIED", "无权操作该差旅单，归属用户不匹配。"));
            }

            TravelOrderStatus currentStatus = order.getStatus();
            if (TravelOrderStatus.CANCELLED == currentStatus) {
                return toJson(ErrorResult.of("INVALID_STATE", "差旅单已处于取消状态，无需重复取消。"));
            }
            if (TravelOrderStatus.COMPLETED == currentStatus) {
                return toJson(ErrorResult.of("INVALID_STATE", "已完成的差旅单不可取消。"));
            }
            if (TravelOrderStatus.APPROVED == currentStatus && !Boolean.TRUE.equals(force)) {
                return toJson(new ConfirmRequiredResult(false, true, orderId, currentStatus.getCode(), "该差旅单已审批通过，取消后不可恢复且可能影响已预订行程。请用户明确确认后再操作（传入 force=true）。"));
            }

            // 取消差旅单
            boolean orderCancelled = travelOrderRepository.updateStatus(orderId, TravelOrderStatus.CANCELLED);
            if (!orderCancelled) {
                return toJson(ErrorResult.of("UPDATE_FAILED", "差旅单状态更新失败，请稍后重试。"));
            }

            // 同步撤销关联审批单
            boolean approvalCancelled = false;
            String cancelledApprovalId = null;
            String approvalId = order.getApprovalId();
            if (!isBlank(approvalId)) {
                approvalCancelled = approvalService.cancel(approvalId);
                cancelledApprovalId = approvalId;
            } else {
                Optional<ApprovalRecord> approvalOpt = approvalService.findLatestByOrderId(orderId);
                if (approvalOpt.isPresent()) {
                    ApprovalRecord ar = approvalOpt.get();
                    if (ApprovalRecordStatus.CANCELLED != ar.getStatus()) {
                        approvalCancelled = approvalService.cancel(ar.getProcessInstanceId());
                        cancelledApprovalId = ar.getProcessInstanceId();
                    }
                }
            }

            String msg = "出差申请已取消。" + (approvalCancelled ? "关联审批单已同步撤销。" : "");
            logger.info("[TOOL][cancel_travel_order] orderId={} 已取消，关联审批撤销={}", orderId, approvalCancelled);

            // 查库验证取消状态
            String cancelVerifyErr = verifyOrderStatus(orderId, TravelOrderStatus.CANCELLED, "cancel_travel_order");
            if (cancelVerifyErr != null) {
                return cancelVerifyErr;
            }

            // 查询关联预订记录
            List<BookingSummary> associatedBookings = queryAssociatedBookings(userId, orderId);
            String affectedMsg = buildAffectedBookingsMessage(associatedBookings);

            return toJson(new CancelResult(true, orderId, TravelOrderStatus.CANCELLED.getCode(), approvalCancelled, cancelledApprovalId, reason, msg, associatedBookings, affectedMsg));

        } catch (Exception e) {
            logger.error("[TOOL][cancel_travel_order] 取消失败 orderId={}", orderId, e);
            return toJson(ErrorResult.of("INTERNAL_ERROR", "取消出差申请失败：" + e.getMessage()));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 修改出差申请
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(name = "modify_travel_order", description = "修改出差申请：更新差旅单中的一个或多个字段（目的地/出发城市/日期/事由），" + "同时撤销旧审批单并发起新的审批流程。" + "APPROVED 状态的差旅单修改需传入 force=true 并获得用户明确确认。" + "若提供日期字段，必须为 YYYY-MM-DD 格式，否则返回参数错误。")
    public String modifyTravelOrder(AgentSessionContext sessionCtx, @ToolParam(name = "order_id", description = "要修改的差旅单ID") String orderId, @ToolParam(name = "destination", description = "新目的地城市，可选", required = false) String destination, @ToolParam(name = "departure_city", description = "新出发城市，可选", required = false) String departureCity, @ToolParam(name = "departure_date", description = "新出发日期，YYYY-MM-DD 格式，可选", required = false) String departureDate, @ToolParam(name = "return_date", description = "新返回日期，YYYY-MM-DD 格式，可选", required = false) String returnDate, @ToolParam(name = "purpose", description = "新出差事由，可选", required = false) String purpose, @ToolParam(name = "force", description = "是否强制修改已审批通过的差旅单，默认 false", required = false) Boolean force) {

        String userId = sessionCtx.getUserId();
        logger.info("[TOOL][modify_travel_order] userId={}, orderId={}", userId, orderId);

        if (isBlank(userId)) {
            return toJson(ErrorResult.of("INVALID_PARAM", "user_id 不能为空"));
        }
        if (isBlank(orderId)) {
            return toJson(ErrorResult.of("INVALID_PARAM", "order_id 不能为空"));
        }

        // 日期字段若提供则必须符合格式
        if (!isBlank(departureDate)) {
            String err = validateDate(departureDate, "departure_date（出发日期）");
            if (err != null) {
                return toJson(ErrorResult.of("INVALID_DATE", err));
            }
        }
        if (!isBlank(returnDate)) {
            String err = validateDate(returnDate, "return_date（返回日期）");
            if (err != null) {
                return toJson(ErrorResult.of("INVALID_DATE", err));
            }
        }

        try {
            Optional<TravelOrder> orderOpt = travelOrderRepository.findByOrderId(orderId);
            if (orderOpt.isEmpty()) {
                return toJson(ErrorResult.of("ORDER_NOT_FOUND", "差旅单不存在：" + orderId));
            }

            TravelOrder order = orderOpt.get();
            if (!userId.equals(order.getUserId())) {
                return toJson(ErrorResult.of("PERMISSION_DENIED", "无权操作该差旅单，归属用户不匹配。"));
            }

            TravelOrderStatus currentStatus = order.getStatus();
            if (TravelOrderStatus.CANCELLED == currentStatus) {
                return toJson(ErrorResult.of("INVALID_STATE", "已取消的差旅单不可修改，请重新提交新的出差申请。"));
            }
            if (TravelOrderStatus.COMPLETED == currentStatus) {
                return toJson(ErrorResult.of("INVALID_STATE", "已完成的差旅单不可修改。"));
            }
            if (TravelOrderStatus.APPROVED == currentStatus && !Boolean.TRUE.equals(force)) {
                return toJson(new ConfirmRequiredResult(false, true, orderId, currentStatus.getCode(), "该差旅单已审批通过，修改将撤销当前审批并重新发起新流程，请用户明确确认后继续（传入 force=true）。"));
            }

            // 校验修改后的日期范围（综合新旧值）
            String effectiveDepartureDate = !isBlank(departureDate) ? departureDate : order.getDepartureDate();
            String effectiveReturnDate = !isBlank(returnDate) ? returnDate : order.getReturnDate();
            if (!isBlank(effectiveDepartureDate) && !isBlank(effectiveReturnDate)) {
                if (LocalDate.parse(effectiveDepartureDate).isAfter(LocalDate.parse(effectiveReturnDate))) {
                    return toJson(ErrorResult.of("INVALID_DATE_RANGE", "出发日期（" + effectiveDepartureDate + "）不能晚于返回日期（" + effectiveReturnDate + "）"));
                }
            }

            // 幂等/空操作短路：所有提交字段与当前值一致时，无需撤审重提，直接返回
            boolean noChange = (isBlank(destination) || equalsSafe(destination, order.getDestination())) && (isBlank(departureCity) || equalsSafe(departureCity, order.getDepartureCity())) && (isBlank(departureDate) || equalsSafe(departureDate, order.getDepartureDate())) && (isBlank(returnDate) || equalsSafe(returnDate, order.getReturnDate())) && (isBlank(purpose) || equalsSafe(purpose, order.getPurpose()));
            if (noChange) {
                logger.info("[TOOL][modify_travel_order] 命中幂等：提交字段与当前值一致，orderId={}，跳过撤审重提", orderId);
                String currentApprovalId = order.getApprovalId();
                return toJson(new ModifyResult(true, orderId, order.getStatus().getCode(), currentApprovalId, false, currentApprovalId, null, "无字段变更", "提交的信息与当前差旅单一致，未做任何修改。", List.of(), null));
            }

            // 更新有变化的字段
            if (!isBlank(destination)) {
                order.setDestination(destination);
            }
            if (!isBlank(departureCity)) {
                order.setDepartureCity(departureCity);
            }
            if (!isBlank(departureDate)) {
                order.setDepartureDate(departureDate);
            }
            if (!isBlank(returnDate)) {
                order.setReturnDate(returnDate);
            }
            if (!isBlank(purpose)) {
                order.setPurpose(purpose);
            }

            // 撤销旧审批单
            boolean oldApprovalCancelled = false;
            String oldApprovalId = order.getApprovalId();
            if (!isBlank(oldApprovalId)) {
                oldApprovalCancelled = approvalService.cancel(oldApprovalId);
            } else {
                Optional<ApprovalRecord> approvalOpt = approvalService.findLatestByOrderId(orderId);
                if (approvalOpt.isPresent()) {
                    oldApprovalId = approvalOpt.get().getProcessInstanceId();
                    oldApprovalCancelled = approvalService.cancel(oldApprovalId);
                }
            }

            // 重置为草稿并保存字段变更
            order.setStatus(TravelOrderStatus.DRAFT);
            order.setApprovalId(null);
            travelOrderRepository.save(order);

            // 提交新审批单
            String approvalForm = buildApprovalForm(orderId, userId, order.getPurpose(), order.getDestination(), order.getDepartureCity(), order.getDepartureDate(), order.getReturnDate());
            ApprovalRecord newRecord = approvalService.submit(userId, order.getPurpose(), approvalForm, orderId);

            // 差旅单关联新审批单
            order.setApprovalId(newRecord.getProcessInstanceId());
            order.setStatus(TravelOrderStatus.SUBMITTED);
            travelOrderRepository.save(order);

            // 查库验证修改后状态
            String modifyVerifyErr = verifyOrderStatus(orderId, TravelOrderStatus.SUBMITTED, "modify_travel_order");
            if (modifyVerifyErr != null) {
                return modifyVerifyErr;
            }

            String updatedFields = buildUpdatedFields(destination, departureCity, departureDate, returnDate, purpose);
            logger.info("[TOOL][modify_travel_order] orderId={} 修改完成，新审批={}", orderId, newRecord.getProcessInstanceId());

            // 查询关联预订记录
            List<BookingSummary> associatedBookings = queryAssociatedBookings(userId, orderId);
            String affectedMsg = buildAffectedBookingsMessage(associatedBookings);
            
            return toJson(new ModifyResult(true, orderId, TravelOrderStatus.SUBMITTED.getCode(), oldApprovalId, oldApprovalCancelled, newRecord.getProcessInstanceId(), newRecord.getStatus().getCode(), updatedFields, "差旅申请已修改，旧审批单已撤销，新审批单已重新提交，等待审批结果。", associatedBookings, affectedMsg));

        } catch (Exception e) {
            logger.error("[TOOL][modify_travel_order] 修改失败 orderId={}", orderId, e);
            return toJson(ErrorResult.of("INTERNAL_ERROR", "修改出差申请失败：" + e.getMessage()));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 私有工具方法
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 将强类型结果对象序列化为 JSON 字符串
     */
    private static String toJson(Object obj) {
        return JSON.toJSONString(obj);
    }

    /**
     * 校验日期字段格式（YYYY-MM-DD）。
     *
     * @return null 表示合法；否则返回错误描述
     */
    private static String validateDate(String date, String fieldName) {
        if (date == null || date.isBlank()) {
            return fieldName + " 不能为空";
        }
        if (!DATE_PATTERN.matcher(date).matches()) {
            return fieldName + " 格式错误，必须为 YYYY-MM-DD（如 2026-07-15），实际值为：" + date;
        }
        try {
            LocalDate.parse(date);
            return null;
        } catch (DateTimeParseException e) {
            return fieldName + " 不是有效的日期，必须为 YYYY-MM-DD，实际值为：" + date;
        }
    }

    /**
     * 构建审批表单 JSON 字符串
     */
    private static String buildApprovalForm(String orderId, String userId, String purpose, String destination, String departureCity, String departureDate, String returnDate) {
        return "{" + "\"orderId\":\"" + orderId + "\"," + "\"userId\":\"" + userId + "\"," + "\"purpose\":\"" + purpose + "\"," + "\"destination\":\"" + destination + "\"," + "\"departureCity\":\"" + departureCity + "\"," + "\"departureDate\":\"" + departureDate + "\"," + "\"returnDate\":\"" + returnDate + "\"" + "}";
    }

    /**
     * 汇总已修改的字段名称
     */
    private static String buildUpdatedFields(String destination, String departureCity, String departureDate, String returnDate, String purpose) {
        List<String> fields = new ArrayList<>();
        if (!isBlank(destination)) {
            fields.add("目的地");
        }
        if (!isBlank(departureCity)) {
            fields.add("出发城市");
        }
        if (!isBlank(departureDate)) {
            fields.add("出发日期");
        }
        if (!isBlank(returnDate)) {
            fields.add("返回日期");
        }
        if (!isBlank(purpose)) {
            fields.add("出差事由");
        }
        return fields.isEmpty() ? "无字段变更" : String.join("、", fields);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * 查询行程关联的有效预订记录（排除已取消/已退款）
     */
    private List<BookingSummary> queryAssociatedBookings(String userId, String orderId) {
        List<BookingRecord> records = bookingRecordRepository.findByUserIdAndTravelOrderId(userId, orderId);
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        return records.stream()
                .filter(r -> r.getStatus() != BookingStatus.CANCELLED && r.getStatus() != BookingStatus.REFUNDED)
                .map(r -> new BookingSummary(
                        r.getBookingId(),
                        r.getBizType() != null ? r.getBizType().getCode() : null,
                        r.getTitle(),
                        r.getStatus() != null ? r.getStatus().getCode() : null,
                        r.getExternalOrderNo()))
                .toList();
    }

    /**
     * 根据关联预订生成提示信息，引导 LLM 告知用户受影响的预订
     */
    private String buildAffectedBookingsMessage(List<BookingSummary> bookings) {
        if (bookings == null || bookings.isEmpty()) {
            return null;
        }
        long flights = bookings.stream().filter(b -> "FLIGHT".equals(b.bizType())).count();
        long hotels = bookings.stream().filter(b -> "HOTEL".equals(b.bizType())).count();
        long trains = bookings.stream().filter(b -> "TRAIN".equals(b.bizType())).count();
        StringBuilder sb = new StringBuilder();
        sb.append("该行程存在 ").append(bookings.size()).append(" 条关联预订（");
        List<String> parts = new ArrayList<>();
        if (flights > 0) parts.add("机票" + flights + "张");
        if (hotels > 0) parts.add("酒店" + hotels + "间");
        if (trains > 0) parts.add("火车票" + trains + "张");
        sb.append(String.join("、", parts));
        sb.append("），请提醒用户是否需要取消或调整这些预订。");
        return sb.toString();
    }

    /**
     * 空安全的字符串相等判断
     */
    private static boolean equalsSafe(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    /**
     * 幂等校验：查找与出行要素完全一致且处于生效状态（DRAFT/SUBMITTED/APPROVED）的差旅单。
     * <p>命中说明是重复提交，返回已有单；无重复返回 {@code null}。
     */
    private TravelOrder findDuplicateActiveOrder(String userId, String destination, String departureCity, String departureDate, String returnDate, String purpose) {
        List<TravelOrder> actives = travelOrderRepository.findActiveByUserIdAndDateRange(userId, List.of(TravelOrderStatus.DRAFT, TravelOrderStatus.SUBMITTED, TravelOrderStatus.APPROVED), departureDate, returnDate);
        for (TravelOrder o : actives) {
            if (equalsSafe(o.getDestination(), destination) && equalsSafe(o.getDepartureCity(), departureCity) && equalsSafe(o.getDepartureDate(), departureDate) && equalsSafe(o.getReturnDate(), returnDate) && equalsSafe(o.getPurpose(), purpose)) {
                return o;
            }
        }
        return null;
    }

    /**
     * 基于已存在的差旅单构建 submit 幂等响应，补齐关联审批单信息。
     */
    private String buildExistingSubmitResult(TravelOrder order) {
        String approvalId = order.getApprovalId();
        String approvalStatus = null;
        String submitTime = null;
        Optional<ApprovalRecord> approvalOpt = !isBlank(approvalId) ? approvalService.findByProcessInstanceId(approvalId) : approvalService.findLatestByOrderId(order.getOrderId());
        if (approvalOpt.isPresent()) {
            ApprovalRecord ar = approvalOpt.get();
            approvalId = ar.getProcessInstanceId();
            approvalStatus = ar.getStatus() != null ? ar.getStatus().getCode() : null;
            submitTime = ar.getSubmitTime() != null ? ar.getSubmitTime().toString() : null;
        }
        return toJson(new SubmitResult(true, order.getOrderId(), approvalId, order.getStatus().getCode(), approvalStatus, submitTime, order.getDestination(), order.getDepartureCity(), order.getDepartureDate(), order.getReturnDate(), order.getPurpose(), String.format("检测到相同出行信息的差旅申请已存在（差旅单ID: %s），已直接返回该申请，未重复创建。", order.getOrderId())));
    }

    /**
     * 查库验证差旅单状态，确保写操作真正落库成功。
     * <p>验证通过返回 {@code null}；验证失败返回包含错误信息的 JSON 字符串，调用方可直接 return。
     *
     * @param orderId        差旅单 ID
     * @param expectedStatus 期望落库后的状态
     * @param toolName       工具名称（用于日志）
     */
    private String verifyOrderStatus(String orderId, TravelOrderStatus expectedStatus, String toolName) {
        Optional<TravelOrder> verifyOpt = travelOrderRepository.findByOrderId(orderId);
        if (verifyOpt.isEmpty()) {
            logger.error("[TOOL][{}] 验证失败：查库后差旅单不存在 orderId={}", toolName, orderId);
            return toJson(ErrorResult.of("VERIFY_FAILED", "操作已执行但验证失败：差旅单 " + orderId + " 查询不到，请联系管理员。"));
        }
        TravelOrderStatus actualStatus = verifyOpt.get().getStatus();
        if (actualStatus != expectedStatus) {
            logger.error("[TOOL][{}] 状态验证失败：期望 {}，实际 {} orderId={}", toolName, expectedStatus.getCode(), actualStatus.getCode(), orderId);
            return toJson(ErrorResult.of("VERIFY_FAILED", String.format("操作已执行但状态与预期不符，期望 %s，实际 %s，请稍后重试或联系管理员。", expectedStatus.getCode(), actualStatus.getCode())));
        }
        logger.info("[TOOL][{}] 状态验证通过：orderId={}, status={}", toolName, orderId, actualStatus.getCode());
        return null;
    }
}
