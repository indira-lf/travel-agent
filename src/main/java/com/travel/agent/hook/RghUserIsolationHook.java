package com.travel.agent.hook;

import com.travel.agent.context.AgentSessionContext;
import com.travel.agent.context.AgentSessionContextHolder;
import com.travel.agent.memory.RghTokenStore;
import io.agentscope.core.hook.*;
import io.agentscope.core.message.ToolUseBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * rgh CLI 用户隔离 Hook。
 *
 * <p>监听 {@link PreActingEvent}：当检测到 {@code execute_shell_command} 工具调用且命令包含
 * {@code rgh} 时，在命令前注入 {@code env HOME=/tmp/gogo-agent/rgh-users/{userId}}，使 rgh CLI 的 Token
 * 存储在用户专属目录下，而非全局 {@code ~/.hotel-cli/token.json}。</p>
 *
 * <p>监听 {@link PostActingEvent}：当 login 命令执行完毕后，将新 Token 回写到 Redis；
 * logout 命令执行后，从 Redis 删除 Token。确保跨节点的 Token 共享。</p>
 *
 * <p><b>此方案不替换 ShellCommandTool，通过修改命令字符串实现隔离，
 * 规避了 SkillBox 内部 cloneShellToolWithWorkDir 导致的子类丢失问题。</b></p>
 *
 * @author Hollis
 */
@Component
public class RghUserIsolationHook implements Hook {

    private static final Logger logger = LoggerFactory.getLogger(RghUserIsolationHook.class);

    private static final String SHELL_TOOL_NAME = "execute_shell_command";
    private static final String USER_HOME_BASE = "/tmp/gogo-agent/rgh-users";

    @Autowired
    private RghTokenStore tokenStore;

    /**
     * 缓存当前请求中被修改过的 rgh 命令，供 PostActingEvent 判断是否为 login/logout。
     * Key: agentId, Value: 原始命令字符串。
     */
    private final Map<String, String> lastRghCommand = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public int priority() {
        // 高于 SessionPersistenceHook (10) 和 ProgressNotifierHook，确保在它们之前处理
        return 5;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PreActingEvent preActing) {
            logger.debug("[RghIsolation] PreActingEvent, toolUse={}", preActing.getToolUse());
            return handlePreActing(preActing).map(e -> {
                @SuppressWarnings("unchecked")
                T casted = (T) e;
                return casted;
            });
        }
        if (event instanceof PostActingEvent postActing) {
            return handlePostActing(postActing).map(e -> {
                @SuppressWarnings("unchecked")
                T casted = (T) e;
                return casted;
            });
        }
        return Mono.just(event);
    }

    private Mono<PreActingEvent> handlePreActing(PreActingEvent event) {
        ToolUseBlock toolUse = event.getToolUse();
        if (toolUse == null || !SHELL_TOOL_NAME.equals(toolUse.getName())) {
            return Mono.just(event);
        }

        Map<String, Object> input = toolUse.getInput();
        if (input == null) {
            return Mono.just(event);
        }

        String command = (String) input.get("command");
        if (command == null || !command.contains("rgh ")) {
            return Mono.just(event);
        }

        // 获取当前用户 ID
        AgentSessionContext sessionCtx = AgentSessionContextHolder.get();
        if (sessionCtx == null) {
            logger.warn("[RghIsolation] AgentSessionContext 为 null，跳过用户隔离");
            return Mono.just(event);
        }

        String userId = sessionCtx.getUserId();
        String agentId = event.getAgent() != null ? event.getAgent().getAgentId() : "unknown";
        Path userDir = Path.of(USER_HOME_BASE, userId);
        Path tokenDir = userDir.resolve(".hotel-cli");

        try {
            // 创建用户专属目录
            Files.createDirectories(tokenDir);

            // 从 Redis 恢复 Token
            String tokenJson = tokenStore.getToken(userId);
            if (tokenJson != null) {
                Files.writeString(tokenDir.resolve("token.json"), tokenJson, StandardCharsets.UTF_8);
                logger.debug("[RghIsolation] 已从 Redis 恢复 Token, userId={}", userId);
            }

            // 将原始命令包装为：env HOME=<userDir> bash -c '<originalCommand>'
            // ShellCommandTool 内部会执行：bash -c "<wrappedCommand>"
            // 最终效果：bash -c "env HOME=/tmp/rgh-users/user123 bash -c 'rgh whoami'"
            String escapedCommand = command.replace("'", "'\\''");
            String wrappedCommand = "env HOME=" + userDir + " bash -c '" + escapedCommand + "'";

            // 创建新的 ToolUseBlock，替换原始命令
            Map<String, Object> newInput = new HashMap<>(input);
            newInput.put("command", wrappedCommand);

            ToolUseBlock newToolUse = new ToolUseBlock(
                    toolUse.getId(),
                    toolUse.getName(),
                    newInput,
                    toolUse.getContent(),
                    toolUse.getMetadata()
            );

            event.setToolUse(newToolUse);

            // 缓存原始命令供 PostActingEvent 使用
            lastRghCommand.put(agentId, command);

            logger.info("[RghIsolation] rgh 命令已注入用户隔离 HOME, userId={}, originalCmd={}",
                    userId, command.length() > 80 ? command.substring(0, 80) + "..." : command);

        } catch (Exception e) {
            logger.error("[RghIsolation] 注入用户隔离环境失败, userId={}", userId, e);
        }

        return Mono.just(event);
    }

    private Mono<PostActingEvent> handlePostActing(PostActingEvent event) {
        ToolUseBlock toolUse = event.getToolUse();
        if (toolUse == null || !SHELL_TOOL_NAME.equals(toolUse.getName())) {
            return Mono.just(event);
        }

        String agentId = event.getAgent() != null ? event.getAgent().getAgentId() : "unknown";
        String originalCommand = lastRghCommand.remove(agentId);
        if (originalCommand == null) {
            return Mono.just(event);
        }

        AgentSessionContext sessionCtx = AgentSessionContextHolder.get();
        if (sessionCtx == null) {
            return Mono.just(event);
        }

        String userId = sessionCtx.getUserId();
        Path tokenFile = Path.of(USER_HOME_BASE, userId, ".hotel-cli", "token.json");

        try {
            // whoami 命令：检查登录状态，如果已登录且 Token 文件存在，同步到 Redis（支持集群模式）
            if (originalCommand.contains("whoami") && !originalCommand.contains("--help")) {
                if (Files.exists(tokenFile)) {
                    String tokenJson = Files.readString(tokenFile, StandardCharsets.UTF_8);
                    tokenStore.saveToken(userId, tokenJson);
                    logger.info("[RghIsolation] rgh whoami 后同步 Token 到 Redis, userId={}", userId);
                }
            }

            // logout 命令：从 Redis 删除 Token
            if (originalCommand.contains("logout")) {
                tokenStore.deleteToken(userId);
                logger.info("[RghIsolation] 已退出登录，Token 已从 Redis 删除, userId={}", userId);
            }
        } catch (Exception e) {
            logger.error("[RghIsolation] Token 后处理失败, userId={}", userId, e);
        }

        return Mono.just(event);
    }
}
