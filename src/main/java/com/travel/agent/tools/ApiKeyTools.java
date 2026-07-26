package com.travel.agent.tools;

import com.travel.agent.context.AgentSessionContext;
import com.travel.apikey.service.ApiKeyService;
import com.travel.apikey.entity.UserApiKeyEntry;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 第三方 API Key 统一管理工具集。
 *
 * <p>各 provider 独立工具名（{@code check_xxx_api_key} / {@code save_xxx_api_key}）。
 * Agent 流程：先调 {@code check_xxx_api_key} 检查状态，
 * 若未配置则引导用户前往获取页面，用户提供后调 {@code save_xxx_api_key} 保存。</p>
 *
 * <p>添加新 provider 流程：
 * <ol>
 *   <li>在 {@link #PROVIDERS} 中添加一条配置（provider 名 / 获取 URL / Key 前缀）</li>
 *   <li>新增一对 {@code @Tool} 方法（check / save）</li>
 * </ol>
 *
 * @author Hollis
 */
@Component
public class ApiKeyTools {

    private static final Logger logger = LoggerFactory.getLogger(ApiKeyTools.class);

    /**
     * Provider 配置：name（与 {@link UserApiKeyEntry#provider} 对齐）/ 获取 URL / Key 前缀校验。
     */
    private record ProviderConfig(String name, String guideUrl, String keyPrefix) {}

    /**
     * 已支持的第三方服务 provider 配置。
     * 使用 {@link LinkedHashMap} 保证迭代顺序与展示一致。
     */
    private static final Map<String, ProviderConfig> PROVIDERS = new LinkedHashMap<>();
    static {
        PROVIDERS.put("flight-manager",
                new ProviderConfig("flight-manager", "https://h5.133.cn/webapp/pages/mcpApiKey", "sk_"));
        PROVIDERS.put("tuniu-cli",
                new ProviderConfig("tuniu-cli", "https://open.tuniu.com/mcp/login", "sk-"));
    }

    @Autowired
    private ApiKeyService apiKeyService;

    // ──────────────────────────────────────────────────────────────────────────
    // 机票服务（flight-manager）
    // ──────────────────────────────────────────────────────────────────────────

    @Tool(name = "check_flight_api_key",
          description = "检查当前用户是否已配置机票服务的 API Key。"
                      + "在执行任何航班搜索、预订等操作之前必须先调用此工具。"
                      + "若返回 hasKey=false，需要引导用户前往 https://h5.133.cn/webapp/pages/mcpApiKey 获取 API Key，"
                      + "然后使用 save_flight_api_key 工具保存。")
    public Map<String, Object> checkFlightApiKey(AgentSessionContext sessionCtx) {
        ProviderConfig cfg = PROVIDERS.get("flight-manager");
        String userId = sessionCtx.getUserId();
        boolean hasKey = apiKeyService.hasApiKey(userId, cfg.name());
        logger.info("[TOOL][check_flight_api_key] userId={}, hasKey={}", userId, hasKey);

        if (hasKey) {
            return Map.of(
                    "hasKey", true,
                    "message", "✅ 机票 API Key 已配置，可以直接使用航班服务。"
            );
        } else {
            return Map.of(
                    "hasKey", false,
                    "message", "❌ 尚未配置机票 API Key。请引导用户前往 " + cfg.guideUrl()
                             + " 获取 API Key，获取后请用户提供 Key，然后调用 save_flight_api_key 保存。"
            );
        }
    }

    @Tool(name = "save_flight_api_key",
          description = "保存用户的机票 API Key。用户从 https://h5.133.cn/webapp/pages/mcpApiKey 获取 Key 后，"
                      + "调用此工具加密保存，后续航班操作自动复用。")
    public Map<String, Object> saveFlightApiKey(
            AgentSessionContext sessionCtx,
            @ToolParam(name = "api_key", description = "用户提供的机票 API Key（sk_per_ 开头的字符串）") String apiKey) {
        return saveApiKey("flight-manager", sessionCtx, apiKey, "机票");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 途牛服务（tuniu-cli）
    // ──────────────────────────────────────────────────────────────────────────

    @Tool(name = "check_tuniu_api_key",
          description = "检查当前用户是否已配置途牛旅行服务的 API Key（TUNIU_API_KEY）。"
                      + "在执行任何机票、酒店、火车票等途牛服务调用之前必须先调用此工具。"
                      + "若返回 hasKey=false，需要引导用户前往 https://open.tuniu.com/mcp/login 获取 API Key，"
                      + "然后使用 save_tuniu_api_key 工具保存。")
    public Map<String, Object> checkTuniuApiKey(AgentSessionContext sessionCtx) {
        ProviderConfig cfg = PROVIDERS.get("tuniu-cli");
        String userId = sessionCtx.getUserId();
        boolean hasKey = apiKeyService.hasApiKey(userId, cfg.name());
        logger.info("[TOOL][check_tuniu_api_key] userId={}, hasKey={}", userId, hasKey);

        if (hasKey) {
            return Map.of(
                    "hasKey", true,
                    "message", "✅ 途牛 API Key 已配置，可以直接使用途牛旅行服务。"
            );
        } else {
            return Map.of(
                    "hasKey", false,
                    "message", "❌ 尚未配置途牛 API Key。请引导用户前往 " + cfg.guideUrl()
                             + " 获取 API Key，获取后请用户提供 Key，然后调用 save_tuniu_api_key 保存。"
            );
        }
    }

    @Tool(name = "save_tuniu_api_key",
          description = "保存用户的途牛 API Key。用户从 https://open.tuniu.com/mcp/login 获取 Key 后，"
                      + "调用此工具加密保存，后续途牛服务调用自动复用。")
    public Map<String, Object> saveTuniuApiKey(
            AgentSessionContext sessionCtx,
            @ToolParam(name = "api_key", description = "用户提供的途牛 API Key（sk- 开头的字符串）") String apiKey) {
        return saveApiKey("tuniu-cli", sessionCtx, apiKey, "途牛");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 私有方法：统一的 check / save 逻辑
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 统一的 API Key 保存流程：空值校验 → 前缀校验 → 加密存储。
     *
     * @param providerKey PROVIDERS Map 中的 key（如 "flight-manager"）
     * @param sessionCtx  会话上下文（取 userId）
     * @param apiKey      用户输入的明文 Key
     * @param displayName 中文显示名（用于提示信息，如"机票"/"途牛"）
     */
    private Map<String, Object> saveApiKey(String providerKey, AgentSessionContext sessionCtx,
                                           String apiKey, String displayName) {
        ProviderConfig cfg = PROVIDERS.get(providerKey);
        String userId = sessionCtx.getUserId();

        if (apiKey == null || apiKey.isBlank()) {
            return Map.of("success", false, "message", "API Key 不能为空");
        }

        if (!apiKey.startsWith(cfg.keyPrefix())) {
            return Map.of("success", false,
                    "message", "API Key 格式不正确，应以 " + cfg.keyPrefix() + " 开头");
        }

        apiKeyService.saveApiKey(userId, cfg.name(), apiKey.trim());
        logger.info("[TOOL][save_{}_api_key] userId={}, keyPrefix={}", providerKey, userId,
                apiKey.length() > 8 ? apiKey.substring(0, 8) + "..." : "***");

        return Map.of(
                "success", true,
                "message", "✅ " + displayName + " API Key 已保存，后续服务调用将自动使用。"
        );
    }
}
