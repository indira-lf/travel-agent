package com.travel.agent.hook;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.travel.agent.context.AgentSessionContext;
import com.travel.agent.context.AgentSessionContextHolder;
import com.travel.business.booking.entity.BookingRecord;
import com.travel.business.booking.entity.BookingStatus;
import com.travel.business.booking.entity.BookingType;
import com.travel.business.booking.repo.BookingRecordRepository;
import com.travel.agent.registry.SseEmitterRegistry;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Mono;

/**
 * 预订记录自动落库 Hook。
 *
 * <p>监听 {@link PostActingEvent}：当 {@code execute_shell_command} 执行的是预订相关命令且返回成功时：
 * <ul>
 *   <li><b>下单类</b>（tuniu 机票/酒店/火车票下单）：解析 CLI stdout 中的外部单号与状态，组装
 *       {@link BookingRecord} 落库；</li>
 *   <li><b>取消类</b>（tuniu 机票/火车票 cancelOrder）：按外部单号定位已有记录，状态同步为
 *       {@link BookingStatus#CANCELLED}。</li>
 * </ul>
 * 「修改」在 tuniu CLI 无独立命令，本质是取消 + 重新下单，已被上述两类覆盖。对 LLM 完全透明，不占用 token。</p>
 *
 * <p>设计对齐 {@link RghUserIsolationHook}：同样在 PostActing 阶段读取工具执行结果做副作用处理。
 * 各平台返回结构差异收敛在本 Hook（Java 侧适配），skill 保持工具无关。</p>
 *
 * @author Hollis
 */
@Component
public class BookingPersistenceHook implements Hook {

    private static final Logger logger = LoggerFactory.getLogger(BookingPersistenceHook.class);

    private static final String SHELL_TOOL_NAME = "execute_shell_command";

    /** 途牛平台标识（与 TuniuApiKeyHook.providerName() 一致） */
    private static final String PLATFORM_TUNIU = "tuniu-cli";

    // ── CLI 命令模式匹配：用于识别下单/取消操作 ────────────────────
    private static final String CMD_FLIGHT_CREATE = "call flight saveOrder";
    private static final String CMD_HOTEL_CREATE = "call hotel tuniuHotelCreateOrder";
    private static final String CMD_TRAIN_CREATE = "call train bookTrain";
    private static final String CMD_FLIGHT_CANCEL = "call flight cancelOrder";
    private static final String CMD_TRAIN_CANCEL = "call train cancelOrder";

    @Autowired
    private BookingRecordRepository bookingRecordRepository;

    @Autowired
    private SseEmitterRegistry emitterRegistry;

    @Override
    public int priority() {
        // 落库为纯副作用，放在命令注入类 Hook（3/5）之后即可
        return 20;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PostActingEvent postActing) {
            try {
                handlePostActing(postActing);
            } catch (Exception e) {
                // 落库失败不得影响主流程
                logger.error("[BookingPersistence] 预订记录落库异常", e);
            }
        }
        return Mono.just(event);
    }

    private void handlePostActing(PostActingEvent event) {
        ToolUseBlock toolUse = event.getToolUse();
        if (toolUse == null || !SHELL_TOOL_NAME.equals(toolUse.getName())) {
            return;
        }
        Map<String, Object> input = toolUse.getInput();
        if (input == null) {
            return;
        }
        String command = (String) input.get("command");
        if (command == null) {
            return;
        }

        // 1. 识别操作类型（下单 / 取消）与业务类型
        BookingOp op = resolveOp(command);
        if (op == null) {
            return;
        }

        // 2. 提取工具执行结果文本并解析为 JSON
        String resultText = extractResultText(event.getToolResult());
        JSONObject resultJson = parseJson(resultText);
        if (resultJson == null) {
            logger.warn("[BookingPersistence] 无法解析结果 JSON，跳过同步, op={}, bizType={}", op.action(), op.bizType());
            return;
        }

        // 3. 仅在操作成功时同步
        if (Boolean.FALSE.equals(resultJson.getBoolean("success"))) {
            logger.info("[BookingPersistence] 操作未成功，跳过同步, op={}, bizType={}", op.action(), op.bizType());
            return;
        }

        JSONObject args = parseArgs(command);
        switch (op.action()) {
            case CREATE -> handleCreate(op.bizType(), resultJson, args);
            case CANCEL -> handleCancel(op.bizType(), args);
            default -> logger.warn("[BookingPersistence] 未知操作类型，跳过同步, op={}, bizType={}", op.action(), op.bizType());
        }
    }

    /** 处理下单成功：解析外部单号并插入新的预订记录。 */
    private void handleCreate(BookingType bizType, JSONObject resultJson, JSONObject args) {
        // 业务结果通常在 result 内层，取不到则退回外层
        JSONObject result = resultJson.getJSONObject("result");
        if (result == null) {
            result = resultJson;
        }
        // MCP 工具响应会把真实业务 JSON 以字符串形式包在 result.content[].text 中，需先解包
        result = unwrapMcpContent(result);

        // 提取外部单号
        String externalOrderNo = firstString(result,
                "orderId", "orderNo", "mainOrderNo", "bookingOrderId", "order_id");
        if (externalOrderNo == null || externalOrderNo.isBlank()) {
            logger.warn("[BookingPersistence] 未从结果中解析到外部单号，跳过落库, bizType={}", bizType);
            return;
        }

        AgentSessionContext sessionCtx = AgentSessionContextHolder.get();
        if (sessionCtx == null) {
            logger.warn("[BookingPersistence] AgentSessionContext 为 null，跳过落库");
            return;
        }
        String userId = sessionCtx.getUserId();

        // 幂等：同平台 + 类型 + 外部单号已存在则不重复落库
        if (bookingRecordRepository.findByExternalOrder(PLATFORM_TUNIU, bizType, externalOrderNo).isPresent()) {
            logger.info("[BookingPersistence] 预订记录已存在，跳过重复落库, orderNo={}", externalOrderNo);
            return;
        }

        // 组装并保存
        BookingRecord record = new BookingRecord();
        record.setUserId(userId);
        record.setConversationId(sessionCtx.getSessionId());
        record.setTravelOrderId(sessionCtx.getTravelOrderId());
        record.setBizType(bizType);
        record.setPlatform(PLATFORM_TUNIU);
        record.setExternalOrderNo(externalOrderNo);
        record.setStatus(BookingStatus.CREATED);
        record.setPaymentStatus("UNPAID");
        record.setBookedAt(Instant.now());

        // 从下单入参（-a '{...}'）中尽力补全展示字段
        enrichFromArgs(record, bizType, args);

        // detail 保留入参与结果全量，便于追溯
        JSONObject detail = new JSONObject();
        detail.put("args", args);
        detail.put("result", result);
        record.setDetail(detail.toJSONString());

        bookingRecordRepository.save(record);
        logger.info("[BookingPersistence] 预订记录已落库, userId={}, bizType={}, platform={}, orderNo={}, bookingId={}",
                userId, bizType, PLATFORM_TUNIU, externalOrderNo, record.getBookingId());

        // 下单成功后向前端推送结构化支付事件（含订单号与支付链接），供渲染“立即支付”卡片
        emitBookingResult(sessionCtx.getSessionId(), record, result);
    }

    /**
     * 向前端推送 {@code booking_result} SSE 事件：包含业务类型、订单号、标题、金额与支付链接。
     * 前端据此渲染带“立即支付”按钮的支付卡片，行为确定、不依赖 LLM 复述链接。
     *
     * <p>事件 JSON 格式：
     * <pre>
     * {
     *   "type": "booking_result",
     *   "bizType": "flight",
     *   "bizTypeLabel": "机票",
     *   "orderId": "外部订单号",
     *   "title": "北京→上海 MU5101",
     *   "amount": "1280.00",
     *   "payUrl": "https://...",
     *   "statusLabel": "待支付"
     * }
     * </pre>
     */
    private void emitBookingResult(String sessionId, BookingRecord record, JSONObject result) {
        if (sessionId == null) {
            return;
        }
        SseEmitter emitter = emitterRegistry.get(sessionId);
        if (emitter == null) {
            return;
        }

        // 支付/详情链接：容错多种字段命名
        String payUrl = firstString(result,
                "payUrl", "paymentUrl", "cashierUrl", "orderDetailH5Url", "orderDetailUrl", "jumpUrl");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", "booking_result");
        data.put("bizType", record.getBizType() != null ? record.getBizType().name().toLowerCase() : "unknown");
        data.put("bizTypeLabel", record.getBizType() != null ? record.getBizType().getLabel() : "预订");
        data.put("orderId", record.getExternalOrderNo());
        data.put("title", record.getTitle());
        data.put("amount", record.getTotalAmount() != null ? record.getTotalAmount().toPlainString() : null);
        data.put("currency", record.getCurrency());
        data.put("payUrl", payUrl);
        data.put("statusLabel", "待支付");

        try {
            emitter.send(SseEmitter.event().name("booking_result").data(JSON.toJSONString(data)));
            logger.info("[BookingPersistence] 已推送 booking_result 事件, sessionId={}, orderNo={}, payUrl={}",
                    sessionId, record.getExternalOrderNo(), payUrl != null ? "有" : "无");
        } catch (IllegalStateException e) {
            logger.debug("[BookingPersistence] booking_result 发送失败，emitter 已完成: {}", e.getMessage());
        } catch (IOException e) {
            logger.debug("[BookingPersistence] booking_result 发送失败（网络异常）: {}", e.getMessage());
        }
    }

    /** 处理取消成功：按外部单号定位已有记录，状态同步为 CANCELLED（幂等）。 */
    private void handleCancel(BookingType bizType, JSONObject args) {
        String externalOrderNo = firstString(args, "orderId", "order_id", "orderNo");
        if (externalOrderNo == null || externalOrderNo.isBlank()) {
            logger.warn("[BookingPersistence] 取消命令未解析到外部单号，跳过同步, bizType={}", bizType);
            return;
        }

        Optional<BookingRecord> existing =
                bookingRecordRepository.findByExternalOrder(PLATFORM_TUNIU, bizType, externalOrderNo);
        if (existing.isEmpty()) {
            logger.info("[BookingPersistence] 未找到对应预订记录，跳过取消同步, bizType={}, orderNo={}", bizType, externalOrderNo);
            return;
        }

        BookingRecord record = existing.get();
        if (record.getStatus() == BookingStatus.CANCELLED) {
            return; // 幂等：已取消无需重复处理
        }
        record.setStatus(BookingStatus.CANCELLED);
        bookingRecordRepository.save(record);
        logger.info("[BookingPersistence] 预订记录已取消, bizType={}, orderNo={}, bookingId={}",
                bizType, externalOrderNo, record.getBookingId());
    }

    /** 根据命令识别操作类型与业务类型；非预订相关命令返回 null。 */
    private BookingOp resolveOp(String command) {
        // 下单类
        if (command.contains(CMD_FLIGHT_CREATE)) {
            return new BookingOp(Action.CREATE, BookingType.FLIGHT);
        }
        if (command.contains(CMD_HOTEL_CREATE)) {
            return new BookingOp(Action.CREATE, BookingType.HOTEL);
        }
        if (command.contains(CMD_TRAIN_CREATE)) {
            return new BookingOp(Action.CREATE, BookingType.TRAIN);
        }
        // 取消类（tuniu 仅机票/火车票支持 cancelOrder）
        if (command.contains(CMD_FLIGHT_CANCEL)) {
            return new BookingOp(Action.CANCEL, BookingType.FLIGHT);
        }
        if (command.contains(CMD_TRAIN_CANCEL)) {
            return new BookingOp(Action.CANCEL, BookingType.TRAIN);
        }
        return null;
    }

    /** 预订相关操作类型。 */
    private enum Action {
        /**
         * 创建
         */
        CREATE,
        /**
         * 取消
         */
        CANCEL
    }

    /** 命令解析结果：操作类型 + 业务类型。 */
    private record BookingOp(Action action, BookingType bizType) {
    }

    /** 根据业务类型从下单入参中补全标题、时间、联系人、金额等展示字段（best-effort）。 */
    private void enrichFromArgs(BookingRecord record, BookingType bizType, JSONObject args) {
        if (args == null) {
            return;
        }
        switch (bizType) {
            case FLIGHT -> {
                record.setTitle(joinNonBlank(" ",
                        joinNonBlank("→", args.getString("departureCityName"), args.getString("arrivalCityName")),
                        args.getString("flightNo")));
                record.setStartTime(toInstant(args.getString("departureDate")));
                JSONObject contact = args.getJSONObject("contactTourist");
                if (contact != null) {
                    record.setContactName(contact.getString("name"));
                    record.setContactPhone(contact.getString("mobile"));
                }
            }
            case HOTEL -> {
                record.setTitle("酒店预订");
                record.setStartTime(toInstant(args.getString("checkInDate")));
                record.setEndTime(toInstant(args.getString("checkOutDate")));
                record.setContactName(args.getString("contactName"));
                record.setContactPhone(args.getString("contactPhone"));
            }
            case TRAIN -> {
                record.setTitle("火车票预订");
                var resources = args.getJSONArray("resources");
                if (resources != null && !resources.isEmpty()) {
                    JSONObject first = resources.getJSONObject(0);
                    record.setStartTime(toInstant(first.getString("departsDate")));
                    BigDecimal price = first.getBigDecimal("adultPrice");
                    if (price != null) {
                        record.setTotalAmount(price);
                    }
                }
                JSONObject contact = args.getJSONObject("contact");
                if (contact != null) {
                    record.setContactPhone(contact.getString("tel"));
                }
            }
            default -> {
                // no-op
            }
        }
    }

    /**
     * 解包 MCP 工具响应：真实业务 JSON 常以字符串形式嵌套在 {@code result.content[].text} 中，
     * 形如 {@code {"content":[{"type":"text","text":"{\"orderId\":\"...\"}"}]}}。
     * 逐个尝试把 text 解析为 JSON，返回第一个可解析出的业务对象；无 content 或均无法解析时原样返回。
     */
    private JSONObject unwrapMcpContent(JSONObject result) {
        if (result == null) {
            return null;
        }
        JSONArray content = result.getJSONArray("content");
        if (content == null || content.isEmpty()) {
            return result;
        }
        for (int i = 0; i < content.size(); i++) {
            JSONObject item = content.getJSONObject(i);
            if (item == null) {
                continue;
            }
            JSONObject inner = parseJson(item.getString("text"));
            if (inner != null) {
                return inner;
            }
        }
        return result;
    }

    /** 从命令中截取 {@code -a '{...}'} 的入参 JSON。 */
    private JSONObject parseArgs(String command) {
        try {
            int first = command.indexOf('{');
            int last = command.lastIndexOf('}');
            if (first >= 0 && last > first) {
                return JSON.parseObject(command.substring(first, last + 1));
            }
        } catch (Exception e) {
            logger.debug("[BookingPersistence] 解析下单入参失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 提取 {@link ToolResultBlock} 中的文本内容。
     * 通过 JSON 序列化每个 ContentBlock 并提取 "text" 字段，兼容不同子类型。
     */
    private String extractResultText(ToolResultBlock toolResult) {
        if (toolResult == null || toolResult.isSuspended() || toolResult.getOutput() == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Object block : toolResult.getOutput()) {
            try {
                JSONObject json = JSON.parseObject(JSON.toJSONString(block));
                String text = json.getString("text");
                if (text != null && !text.isBlank()) {
                    if (sb.length() > 0) {
                        sb.append("\n");
                    }
                    sb.append(text.trim());
                }
            } catch (Exception ignored) {
                // 跳过无法序列化的块
            }
        }
        String result = sb.toString();
        return result.isBlank() ? null : result;
    }

    /** 从可能夹杂日志的文本中提取并解析第一个 JSON 对象。 */
    private JSONObject parseJson(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String trimmed = text.trim();
        try {
            return JSON.parseObject(trimmed);
        } catch (Exception e) {
            int start = trimmed.indexOf('{');
            int end = trimmed.lastIndexOf('}');
            if (start >= 0 && end > start) {
                try {
                    return JSON.parseObject(trimmed.substring(start, end + 1));
                } catch (Exception ignored) {
                    // ignore
                }
            }
        }
        return null;
    }

    /** 依次尝试多个 key，返回第一个非空字符串值。 */
    private String firstString(JSONObject json, String... keys) {
        if (json == null) {
            return null;
        }
        for (String key : keys) {
            Object v = json.get(key);
            if (v != null && !v.toString().isBlank()) {
                return v.toString();
            }
        }
        return null;
    }

    /** 将 "yyyy-MM-dd" 日期字符串转为当天零点的 Instant，失败返回 null。 */
    private Instant toInstant(String date) {
        if (date == null || date.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(date.trim()).atStartOfDay(ZoneId.systemDefault()).toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    /** 用分隔符拼接非空片段，全为空则返回 null。 */
    private String joinNonBlank(String sep, String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p != null && !p.isBlank()) {
                if (sb.length() > 0) {
                    sb.append(sep);
                }
                sb.append(p.trim());
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }
}
