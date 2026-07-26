package com.gogo.travel.agent.tools;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.gogo.travel.agent.context.AgentSessionContext;
import com.gogo.travel.apikey.service.ApiKeyService;
import com.gogo.travel.business.booking.entity.BookingRecord;
import com.gogo.travel.business.booking.entity.BookingStatus;
import com.gogo.travel.business.booking.entity.BookingType;
import com.gogo.travel.business.booking.repo.BookingRecordRepository;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static com.alibaba.dashscope.utils.JsonUtils.toJson;

/**
 * 预订记录写操作工具集。
 *
 * <p>支持机票/火车票通过 tuniu-cli 调用外部平台取消接口，
 * 酒店暂无线上取消通道则仅标记内部状态。</p>
 *
 * @author Hollis
 */
@Component
public class BookingWriteTools {

    private static final Logger log = LoggerFactory.getLogger(BookingWriteTools.class);

    private static final int CLI_TIMEOUT_SECONDS = 30;
    private static final String TUNIU_PROVIDER = "tuniu-cli";

    /**
     * bizType -> tuniu-cli 子命令映射
     */
    private static final Map<BookingType, String> CANCEL_COMMAND_MAP = Map.of(
            BookingType.FLIGHT, "call flight cancelOrder",
            BookingType.TRAIN, "call train cancelOrder"
    );

    private final BookingRecordRepository bookingRecordRepository;
    private final ApiKeyService apiKeyService;

    public BookingWriteTools(BookingRecordRepository bookingRecordRepository, ApiKeyService apiKeyService) {
        this.bookingRecordRepository = bookingRecordRepository;
        this.apiKeyService = apiKeyService;
    }

    // ─────────────────────────────────────── Tool 定义 ───────────────────────────────────────

    @Tool(name = "cancel_booking",
            description = "取消用户的一条外部预订记录。机票和火车票会同步调用平台取消接口；"
                          + "酒店目前无线上取消通道，仅标记内部状态为已取消并提示用户联系酒店。"
                          + "已取消的记录重复调用会直接返回成功（幂等）。")
    public String cancelBooking(
            AgentSessionContext sessionCtx,
            @ToolParam(name = "booking_id", description = "要取消的内部预订单号（如 bk_xxx）") String bookingId,
            @ToolParam(name = "reason", description = "取消原因，可选", required = false) String reason) {

        String userId = sessionCtx.getUserId();
        log.info("[cancel_booking] userId={}, bookingId={}, reason={}", userId, bookingId, reason);

        if (bookingId == null || bookingId.isBlank()) {
            return errorResponse("INVALID_PARAM", "booking_id 不能为空");
        }

        // 1. 查询并校验归属
        BookingRecord record = bookingRecordRepository.findByBookingId(bookingId).orElse(null);
        if (record == null) {
            return errorResponse("BOOKING_NOT_FOUND", "预订记录不存在：" + bookingId);
        }
        if (!userId.equals(record.getUserId())) {
            return errorResponse("PERMISSION_DENIED", "无权操作该预订记录，归属用户不匹配。");
        }

        // 2. 幂等
        BookingType bizType = record.getBizType();
        if (record.getStatus() == BookingStatus.CANCELLED) {
            return successResult(bookingId, bizType, true, "该预订记录已处于取消状态，无需重复取消。");
        }

        // 3. 按业务类型执行平台取消
        PlatformCancelOutcome outcome = cancelOnPlatform(bizType, record.getExternalOrderNo(), userId, bookingId);
        if (!outcome.proceed) {
            return errorResponse("PLATFORM_CANCEL_FAILED", "外部平台取消失败，内部预订状态未变更。原因：" + outcome.message);
        }

        // 4. 更新内部状态
        record.setStatus(BookingStatus.CANCELLED);
        if (reason != null && !reason.isBlank()) {
            record.setRemark(reason);
        }
        bookingRecordRepository.save(record);
        log.info("[cancel_booking] 已取消, bookingId={}, platformCancelled={}", bookingId, outcome.platformCancelled);

        return successResult(bookingId, bizType, outcome.platformCancelled, "预订已取消。" + outcome.message);
    }

    // ─────────────────────────────────────── 平台取消逻辑 ───────────────────────────────────────

    /**
     * 按业务类型执行平台侧取消。
     *
     * @return proceed=true 表示可继续更新内部状态；proceed=false 表示平台取消失败需中止
     */
    private PlatformCancelOutcome cancelOnPlatform(BookingType bizType, String externalOrderNo,
                                                   String userId, String bookingId) {
        if (!CANCEL_COMMAND_MAP.containsKey(bizType)) {
            // 酒店/其他：无需调平台，直接标记
            String msg = (bizType == BookingType.HOTEL)
                    ? "酒店预订暂不支持线上自动取消，已标记内部状态为已取消，请提示用户联系酒店前台处理。"
                    : "该业务类型暂不支持自动取消，已标记内部状态为已取消。";
            return PlatformCancelOutcome.skip(msg);
        }

        if (externalOrderNo == null || externalOrderNo.isBlank()) {
            log.warn("[cancel_booking] 无外部单号，仅标记内部状态, bookingId={}", bookingId);
            return PlatformCancelOutcome.skip("（无外部平台订单号，仅标记内部状态为已取消）");
        }

        // 执行 CLI 取消
        CliResult result = invokeTuniuCancel(CANCEL_COMMAND_MAP.get(bizType), externalOrderNo, userId);
        if (result.success()) {
            log.info("[cancel_booking] 平台取消成功, bookingId={}", bookingId);
            return PlatformCancelOutcome.cancelled("平台取消请求已发送。");
        }

        log.warn("[cancel_booking] 平台取消失败, bookingId={}, error={}", bookingId, result.errorMsg());
        return PlatformCancelOutcome.failed(result.errorMsg());
    }

    /**
     * 调用 tuniu CLI 执行取消命令，通过 env 前缀注入 TUNIU_API_KEY。
     */
    private CliResult invokeTuniuCancel(String subCommand, String externalOrderNo, String userId) {
        String apiKey = apiKeyService.getApiKey(userId, TUNIU_PROVIDER);
        if (apiKey == null || apiKey.isBlank()) {
            return CliResult.fail("用户未配置途牛 API Key，无法调用平台取消接口。");
        }

        // 与 TuniuApiKeyHook 保持一致：用 env 前缀注入 API Key
        String cmd = "env TUNIU_API_KEY=%s tuniu %s -a '{\"orderId\":\"%s\"}'".formatted(apiKey, subCommand, externalOrderNo);
        log.debug("[cancel_booking] CLI cmd: " + cmd);

        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd)
                    .redirectErrorStream(true);

            Process process = pb.start();
            String output;
            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                output = reader.lines().reduce("", (a, b) -> a + b + "\n").trim();
                log.debug("[cancel_booking] CLI output: " + output);
            }

            if (!process.waitFor(CLI_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return CliResult.fail("平台取消请求超时，请稍后重试。");
            }
            if (process.exitValue() != 0) {
                return CliResult.fail("平台接口调用异常（exitCode=" + process.exitValue() + "）");
            }

            return parseCliResponse(output);
        } catch (Exception e) {
            log.error("[cancel_booking] CLI异常", e);
            return CliResult.fail("平台取消接口调用异常：" + e.getMessage());
        }
    }

    // ─────────────────────────────────────── 响应解析 ───────────────────────────────────────

    /**
     * 解析平台响应 JSON。
     * <p>机票成功：{"successCode": true, "msg": "订单取消成功！", "errorCode": 170000}
     * <p>火车票成功：{"successCode": true, "errorMessage": null}
     */
    private CliResult parseCliResponse(String output) {
        if (output == null || output.isBlank()) {
            return CliResult.fail("平台返回空响应");
        }
        int start = output.indexOf('{');
        int end = output.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return CliResult.fail("平台响应格式异常");
        }
        try {
            JSONObject json = JSON.parseObject(output.substring(start, end + 1));
            if (Boolean.TRUE.equals(json.getBoolean("successCode"))) {
                return CliResult.ok(output);
            }
            // 取失败信息：优先 msg，其次 errorMessage
            String errorMsg = Stream.of(json.getString("msg"), json.getString("errorMessage"))
                    .filter(s -> s != null && !s.isBlank())
                    .findFirst()
                    .orElse("平台返回取消失败（未提供具体原因）");
            return CliResult.fail(errorMsg);
        } catch (Exception e) {
            return CliResult.fail("平台响应解析失败");
        }
    }

    // ─────────────────────────────────────── 结果构建 ───────────────────────────────────────

    private String successResult(String bookingId, BookingType bizType, boolean platformCancelled, String message) {
        return toJson(new CancelBookingResult(true, bookingId,
                bizType != null ? bizType.getCode() : null,
                BookingStatus.CANCELLED.getCode(), platformCancelled, message));
    }

    private static String errorResponse(String errorCode, String message) {
        return toJson(new ErrorResult(false, errorCode, message));
    }

    // ─────────────────────────────────────── 内部数据结构 ───────────────────────────────────────

    /**
     * CLI 调用结果
     */
    private record CliResult(boolean success, String output, String errorMsg) {
        static CliResult ok(String output) {
            return new CliResult(true, output, null);
        }

        static CliResult fail(String msg) {
            return new CliResult(false, null, msg);
        }
    }

    /**
     * 平台取消结局：proceed=是否可继续更新内部状态
     */
    private record PlatformCancelOutcome(boolean proceed, boolean platformCancelled, String message) {
        static PlatformCancelOutcome cancelled(String msg) {
            return new PlatformCancelOutcome(true, true, msg);
        }

        static PlatformCancelOutcome skip(String msg) {
            return new PlatformCancelOutcome(true, false, msg);
        }

        static PlatformCancelOutcome failed(String msg) {
            return new PlatformCancelOutcome(false, false, msg);
        }
    }

    record CancelBookingResult(boolean success, String bookingId, String bizType, String newStatus,
                               boolean platformCancelled, String message) {
    }

    record ErrorResult(boolean success, String errorCode, String message) {
    }
}
