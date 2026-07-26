package com.travel.agent.hook;

import org.springframework.stereotype.Component;

/**
 * 机票 API Key 注入 Hook。
 *
 * <p>监听 {@link io.agentscope.core.hook.PreActingEvent}：当检测到 {@code execute_shell_command} 工具调用
 * 且命令包含机票 API 端点时，将命令中的 {@code ${FLIGHT_API_KEY}} 占位符
 * 替换为从数据库/Redis 读取的真实 API Key。</p>
 *
 * @author Hollis
 */
@Component
public class FlightApiKeyHook extends AbstractShellApiKeyHook {

    /** 占位符，由 Skill 模板预埋在命令中 */
    private static final String API_KEY_PLACEHOLDER = "${FLIGHT_API_KEY}";

    /** 机票 API 端点域名，用于快速判断是否为机票命令 */
    private static final String FLIGHT_ENDPOINT_MARKER = "fly.huoli.com";

    @Override
    protected String providerName() {
        return "flight-manager";
    }

    @Override
    protected String logTag() {
        return "FlightApiKey";
    }

    @Override
    protected boolean shouldHandle(String command) {
        // 必须同时包含占位符与机票端点：占位符天然防止重复注入
        return command.contains(API_KEY_PLACEHOLDER) && command.contains(FLIGHT_ENDPOINT_MARKER);
    }

    @Override
    protected String transformCommand(String command, String apiKey) {
        return command.replace(API_KEY_PLACEHOLDER, apiKey);
    }
}
