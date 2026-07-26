package com.gogo.travel.config;

import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 天气查询 MCP 客户端配置。
 *
 * <p>天气查询服务（Streamable HTTP 传输）。该 MCP 作为 wttr.in 的补充：wttr.in 仅支持今日起 3 天内，超出范围时由本 MCP 接管。
 *
 * @author Hollis
 */
@Configuration
public class WeatherMcpConfig {

    private static final Logger logger = LoggerFactory.getLogger(WeatherMcpConfig.class);

    /** MCP 工具调用请求超时（秒） */
    private static final int MCP_REQUEST_TIMEOUT_SECONDS = 30;
    /** MCP 初始化握手超时（秒） */
    private static final int MCP_INIT_TIMEOUT_SECONDS = 15;

    /**
     * 天气 MCP Streamable HTTP 端点，从配置文件读取。
     * 配置项：{@code app.weather-mcp.endpoint}
     */
    @Value("${app.weather-mcp.endpoint}")
    private String weatherMcpEndpoint;

    /**
     * 天气 MCP 客户端 Bean。
     *
     * <p>复用 DashScope API Key（{@code agentscope.dashscope.api-key}），通过
     * {@code Authorization: Bearer <key>} 请求头完成鉴权。
     * 收费服务：https://market.aliyun.com/detail/cmapi00074002#sku=yuncode6800200004
     *
     * @return {@link McpClientWrapper} 单例，可被多个 Agent Toolkit 共享
     */
    @Bean(name = "weatherMcpClient")
    public McpClientWrapper weatherMcpClient() {

        logger.info("[WeatherMcp] 初始化 天气 MCP 客户端 (Streamable HTTP)...");

        try {
            McpClientWrapper client = McpClientBuilder.create("weather-mcp")
                    // Streamable HTTP 传输
                    .streamableHttpTransport(weatherMcpEndpoint)
                    // 工具调用请求超时
                    .timeout(Duration.ofSeconds(MCP_REQUEST_TIMEOUT_SECONDS))
                    // MCP 初始化握手超时
                    .initializationTimeout(Duration.ofSeconds(MCP_INIT_TIMEOUT_SECONDS))
                    .buildAsync()
                    .block();

            logger.info("[WeatherMcp] 天气 MCP 客户端初始化成功");
            return client;

        } catch (Exception e) {
            logger.warn("[WeatherMcp] 天气 MCP 客户端初始化失败，超过 3 天的天气查询将不可用。原因：{}",
                    e.getMessage());
            return null;
        }
    }
}
