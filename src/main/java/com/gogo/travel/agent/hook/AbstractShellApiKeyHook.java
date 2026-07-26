package com.gogo.travel.agent.hook;

import com.gogo.travel.agent.context.AgentSessionContext;
import com.gogo.travel.agent.context.AgentSessionContextHolder;
import com.gogo.travel.apikey.service.ApiKeyService;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.message.ToolUseBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

/**
 * Shell 命令 API Key 注入抽象基类。
 *
 * <p>监听 {@link PreActingEvent}：当检测到 {@code execute_shell_command} 工具调用
 * 且命令命中子类定义的特征时，从 {@link ApiKeyService} 读取当前用户的 API Key，
 * 由子类决定如何注入到命令中。</p>
 *
 * <p>子类只需实现 4 个抽象方法：
 * <ul>
 *   <li>{@link #providerName()} - ApiKeyService 中注册的 provider 名</li>
 *   <li>{@link #logTag()} - 日志前缀，便于排查</li>
 *   <li>{@link #shouldHandle(String)} - 该命令是否需要本 hook 处理（含防重复注入）</li>
 *   <li>{@link #transformCommand(String, String)} - 注入 API Key 后的新命令</li>
 * </ul>
 *
 * @author Hollis
 */
public abstract class AbstractShellApiKeyHook implements Hook {

    /** 需要拦截的 Shell 工具名 */
    protected static final String SHELL_TOOL_NAME = "execute_shell_command";

    /** 子类自报的 logger，自动绑定到具体子类 */
    protected final Logger logger = LoggerFactory.getLogger(getClass());

    @Autowired
    protected ApiKeyService apiKeyService;

    @Override
    public int priority() {
        // 默认 3：在 RghUserIsolationHook (5) 之前注入 API Key
        return 3;
    }

    @Override
    public final <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PreActingEvent preActing) {
            return handlePreActing(preActing).map(e -> {
                @SuppressWarnings("unchecked")
                T casted = (T) e;
                return casted;
            });
        }
        return Mono.just(event);
    }

    /**
     * 处理 PreActing 事件的统一流程：解析 command → shouldHandle 判断 → 读取 API Key → 转换 → 替换 ToolUseBlock。
     */
    protected Mono<PreActingEvent> handlePreActing(PreActingEvent event) {
        ToolUseBlock toolUse = event.getToolUse();
        if (toolUse == null || !SHELL_TOOL_NAME.equals(toolUse.getName())) {
            return Mono.just(event);
        }

        Map<String, Object> input = toolUse.getInput();
        if (input == null) {
            return Mono.just(event);
        }

        String command = (String) input.get("command");
        if (command == null || !shouldHandle(command)) {
            return Mono.just(event);
        }

        AgentSessionContext sessionCtx = AgentSessionContextHolder.get();
        if (sessionCtx == null) {
            logger.warn("[{}] AgentSessionContext 为 null，跳过 API Key 注入", logTag());
            return Mono.just(event);
        }

        logger.debug("[{}] 尝试注入 API Key, command={}", logTag(), command);

        String userId = sessionCtx.getUserId();
        String apiKey = apiKeyService.getApiKey(userId, providerName());
        if (apiKey == null) {
            logger.warn("[{}] 用户未配置 API Key, userId={}", logTag(), userId);
            return Mono.just(event);
        }

        String newCommand = transformCommand(command, apiKey);
        // transformCommand 返回原命令或 null 视为无效，跳过注入
        if (newCommand == null || newCommand.equals(command)) {
            return Mono.just(event);
        }

        Map<String, Object> newInput = new HashMap<>(input);
        newInput.put("command", newCommand);

        ToolUseBlock newToolUse = new ToolUseBlock(
                toolUse.getId(),
                toolUse.getName(),
                newInput,
                toolUse.getContent(),
                toolUse.getMetadata());

        event.setToolUse(newToolUse);

        logger.info("[{}] 已注入 API Key, userId={}", logTag(), userId);

        return Mono.just(event);
    }

    /** ApiKeyService 中注册的 provider 名称 */
    protected abstract String providerName();

    /** 日志标签，便于排查（例如 "FlightApiKey" / "TuniuApiKey"） */
    protected abstract String logTag();

    /**
     * 判断命令是否需要本 hook 处理。
     *
     * <p>子类应在此方法内实现特征匹配与防重复注入逻辑。</p>
     */
    protected abstract boolean shouldHandle(String command);

    /**
     * 注入 API Key 后的新命令。
     *
     * @return 注入后的命令；返回 null 或原 command 表示不注入
     */
    protected abstract String transformCommand(String command, String apiKey);
}
