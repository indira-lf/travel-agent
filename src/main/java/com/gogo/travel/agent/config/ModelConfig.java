package com.gogo.travel.agent.config;

import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.Model;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * @author Hollis
 */
@Configuration
public class ModelConfig {

    @Value("${agentscope.dashscope.api-key:${DASHSCOPE_API_KEY:}}")
    private String apiKey;

    @Bean("fastModel")
    public Model fastModel() {
        return DashScopeChatModel.builder()
                .apiKey(apiKey)
                // 1.2元/百万token ，或者用最便宜的 qwen-flash 也够了，0.15元/百万token
                .modelName("qwen3.6-flash")
                .stream(true)
                .enableThinking(false)
                .build();
    }

    @Bean("strongModel")
    @Primary
    public Model strongModel() {
        return DashScopeChatModel.builder()
                .apiKey(apiKey)
                // 12元/百万token  或者用最新的 kimi/kimi-k3 20元/百万token
                .modelName("qwen3.7-max")
                .stream(true)
                .enableThinking(false)
                .build();
    }

    @Bean("strongModelWithThinking")
    public Model strongModelWithThinking() {
        return DashScopeChatModel.builder()
                .apiKey(apiKey)
                // 12元/百万token  或者用最新的 kimi/kimi-k3 20元/百万token
                .modelName("qwen3.7-max")
                .stream(true)
                // 开启深度思考
                .enableThinking(true)
                .build();
    }

    @Bean("stableModel")
    public Model stableModel() {
        return DashScopeChatModel.builder()
                .apiKey(apiKey)
                //6元/百万token
                .modelName("glm-5.1")
                .stream(true)
                .enableThinking(false)
                .build();
    }
}
