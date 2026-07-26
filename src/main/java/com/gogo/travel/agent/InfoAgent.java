package com.gogo.travel.agent;

import com.gogo.travel.agent.common.PromptLoader;
import com.gogo.travel.agent.memory.AgentMemoryFactory;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.hook.PendingToolRecoveryHook;
import io.agentscope.core.memory.autocontext.AutoContextHook;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.RAGMode;
import io.agentscope.core.tool.Toolkit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

import java.util.List;

import static com.gogo.travel.agent.hook.ToolCircuitBreakerHook.TOOL_CIRCUIT_BREAKER_GROUP;

/**
 * @author Hollis
 */
@Configuration("infoAgentConfiguration")
public class InfoAgent extends BaseSubAgent {

    /** 信息查询 Agent 最大推理迭代次数 */
    private static final int MAX_ITERATIONS = 5;

    /**
     * 景点 RAG 知识库，
     */
    @Autowired
    @Qualifier("attractionKnowledge")
    protected Knowledge attractionKnowledge;
    /**
     * 差旅政策 RAG 知识库，
     */
    @Autowired
    @Qualifier("corporateTravelPolicyKnowledge")
    protected Knowledge corporateTravelPolicyKnowledge;
    /**
     * 差旅指南 RAG 知识库，
     */
    @Autowired
    @Qualifier("corporateTravelGuidelinesKnowledge")
    protected Knowledge corporateTravelGuidelinesKnowledge;

    @Bean(name = "infoAgent")
    @Scope("prototype")
    public ReActAgent build() {
        Toolkit toolkit = new Toolkit();

        toolkit.createToolGroup(TOOL_CIRCUIT_BREAKER_GROUP, TOOL_CIRCUIT_BREAKER_GROUP, true);

        toolkit.registration().tool(infoQueryTools).apply();
        toolkit.registration().tool(policyTools).apply();
        toolkit.registration().tool(destinationLiveTools).group(TOOL_CIRCUIT_BREAKER_GROUP).apply();
        toolkit.registration().mcpClient(weatherMcpClient).enableTools(weatherMcpEnabledTools).apply();
        toolkit.registration().mcpClient(oriznVisaMcpClient).enableTools(oriznMcpEnabledTools).apply();

        ReActAgent agent = ReActAgent.builder()
                .name("InfoAgent")
                .description("信息查询助手，负责查询差旅政策标准、目的地旅游景点、签证入境政策及通用公共信息")
                .model(stableModel)
                .toolkit(toolkit)
                // 从 ThreadLocal 读取当前请求的会话上下文，用于 ToolExecutionContext 注入
                .toolExecutionContext(createToolCtx())
                // 自动压缩/卸载大工具结果与历史，抑制多轮输入 token 膨胀（需搭配 AutoContextHook）
                .memory(AgentMemoryFactory.create(stableModel))
                .hooks(List.of(dynamicTimeInjectionHook, new AutoContextHook(), cliResultCompressHook, toolCircuitBreakerHook, executionLoggerHook, progressNotifierHook, sessionPersistenceHook, new PendingToolRecoveryHook()))
                .sysPrompt(PromptLoader.loadStatic("info-agent-system.md"))
                .maxIters(MAX_ITERATIONS)
                .knowledge(attractionKnowledge).knowledge(corporateTravelPolicyKnowledge).knowledge(corporateTravelGuidelinesKnowledge)
                .toolExecutionConfig(getToolConfig())
                .ragMode(RAGMode.AGENTIC)
                .generateOptions(getGenerateOptions())
                .build();

        return agent;
    }
}
