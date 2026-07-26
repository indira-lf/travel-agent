package com.gogo.travel.config;

import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Orizn Visa MCP 客户端配置。
 *
 * <p>通过 stdio 传输启动 {@code npx -y orizn-visa-mcp} 进程，
 * 将任意两国组合的签证类型、所需文件、费用、处理时间、照片规格、中转规则、
 * 旅行安全提醒等 32 个数据点暴露给 Agent（39,585 个护照-目的地对，15 种语言）。
 *
 * <p>GitHub: <a href="https://github.com/MattJeff/orizn-mcp-server">MattJeff/orizn-mcp-server</a><br>
 * 申请 API Key: <a href="https://visa.orizn.app">visa.orizn.app</a>
 *
 * <p>免费模式（不配置 {@code ORIZN_API_KEY}）仅暴露
 * {@code quick_visa_check} 和 {@code check_visa_requirement} 两个工具；
 * 配置 API Key 后可解锁 {@code check_visa_requirement} / {@code get_all_destinations} /
 * {@code get_visa_changes} / {@code check_transit_visa} 等工具。
 *
 * @author Hollis
 */
@Configuration
public class OriznVisaMcpConfig {

    private static final Logger logger = LoggerFactory.getLogger(OriznVisaMcpConfig.class);

    @Value("${app.orizn-mcp.command:npx}")
    private String command;

    /**
     * 命令参数（逗号分隔）。默认 {@code -y,orizn-visa-mcp}。
     */
    @Value("${app.orizn-mcp.args:-y,orizn-visa-mcp}")
    private String argsConfig;

    /**
     * Orizn Visa API Key（可选）。未配置时走免费模式。
     */
    @Value("${app.orizn-mcp.api-key:}")
    private String apiKey;

    @Value("${app.orizn-mcp.initialization-timeout-seconds:20}")
    private long initializationTimeoutSeconds;

    @Value("${app.orizn-mcp.request-timeout-seconds:30}")
    private long requestTimeoutSeconds;

    /**
     * Orizn Visa MCP stdio 客户端 Bean。
     *
     * <p>当 {@code npx} 不在 PATH、API Key 校验失败或进程握手超时时，本方法返回 {@code null}，
     * 参照 {@link WeatherMcpConfig} 的优雅降级模式，不影响其他工具。
     *
     * @return {@link McpClientWrapper} 单例，可被多个 Agent Toolkit 共享
     */
    @Bean(name = "oriznVisaMcpClient")
    public McpClientWrapper oriznVisaMcpClient() {
        logger.info("[OriznVisaMcp] 初始化 Orizn Visa MCP 客户端 (stdio)...");

        try {
            // 解析 args（逗号分隔）
            List<String> args = argsConfig == null || argsConfig.isBlank()
                    ? List.of()
                    : List.of(argsConfig.split(",")).stream()
                            .map(String::trim)
                            .filter(s -> !s.isBlank())
                            .toList();

            // 构造子进程环境变量：注入 ORIZN_API_KEY（未配置则不注入，走免费模式）
            Map<String, String> env = new HashMap<>();
            if (apiKey != null && !apiKey.isBlank()) {
                env.put("ORIZN_API_KEY", apiKey);
                logger.info("[OriznVisaMcp] 已配置 API Key，启用完整模式");
            } else {
                logger.info("[OriznVisaMcp] 未配置 API Key，使用免费模式（仅 quick_visa_check / get_coverage_stats）");
            }

            McpClientWrapper client = McpClientBuilder.create("orizn-visa-mcp")
                    // stdio 传输：启动 npx -y orizn-visa-mcp 进程，通过 stdio 通信
                    .stdioTransport(command, args, env)
                    // 工具调用请求超时
                    .timeout(Duration.ofSeconds(requestTimeoutSeconds))
                    // MCP 初始化握手超时
                    .initializationTimeout(Duration.ofSeconds(initializationTimeoutSeconds))
                    .buildAsync()
                    .block();

            logger.info("[OriznVisaMcp] Orizn Visa MCP 客户端初始化成功");
            return client;

        } catch (Exception e) {
            logger.warn("[OriznVisaMcp] Orizn Visa MCP 客户端初始化失败，签证查询将降级为内部 RAG 知识库。原因：{}",
                    e.getMessage());
            return null;
        }
    }
}
