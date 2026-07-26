package com.gogo.travel.agent.hook;

import org.springframework.stereotype.Component;

/**
 * 途牛 API Key 注入 Hook。
 *
 * <p>监听 {@link io.agentscope.core.hook.PreActingEvent}：当检测到 {@code execute_shell_command} 工具调用
 * 且命令以 {@code tuniu} 开头时，在命令前注入环境变量 {@code TUNIU_API_KEY}，
 * 使 tuniu CLI 使用当前用户的 API Key。</p>
 *
 * @author Hollis
 */
@Component
public class TuniuApiKeyHook extends AbstractShellApiKeyHook {

    private static final String TUNIU_CMD = "tuniu ";
    private static final String ENV_PREFIX = "TUNIU_API_KEY=";
    private static final String EXPORT_PREFIX = "export TUNIU_API_KEY";
    private static final String ENV_INJECT_PREFIX = "env TUNIU_API_KEY=";

    @Override
    protected String providerName() {
        return "tuniu-cli";
    }

    @Override
    protected String logTag() {
        return "TuniuApiKey";
    }

    @Override
    protected boolean shouldHandle(String command) {
        // 命中 tuniu 命令，且未通过 env 或 export 手动注入过 Key（防重复注入）
        return command.contains(TUNIU_CMD)
                && !command.contains(ENV_PREFIX)
                && !command.contains(EXPORT_PREFIX);
    }

    @Override
    protected String transformCommand(String command, String apiKey) {
        // 用 env 注入环境变量，不影响命令中原有的引号转义
        return ENV_INJECT_PREFIX + apiKey + " " + command;
    }
}
