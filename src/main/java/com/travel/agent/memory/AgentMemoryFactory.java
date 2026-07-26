package com.travel.agent.memory;

import io.agentscope.core.memory.autocontext.AutoContextConfig;
import io.agentscope.core.memory.autocontext.AutoContextMemory;
import io.agentscope.core.model.Model;

/**
 * 统一构建带自动压缩/卸载能力的 {@link AutoContextMemory}，用于抑制多 Agent 多轮对话中的
 * 输入 token 膨胀。
 *
 * @author Hollis
 */
public final class AgentMemoryFactory {

    /** 单条消息超过该字符数即卸载。 */
    private static final long LARGE_PAYLOAD_THRESHOLD = 4 * 1024L;

    /** 卸载后保留的预览长度（字符），足够 LLM 判断是否需要重新拉取完整内容。 */
    private static final int OFFLOAD_SINGLE_PREVIEW = 300;

    /** 上下文窗口 token 上限。 */
    private static final long MAX_TOKEN = 128 * 1024L;

    /** 达到 {@code MAX_TOKEN * TOKEN_RATIO} 即触发压缩。 */
    private static final double TOKEN_RATIO = 0.75;

    /** 消息条数阈值：超过即触发压缩。 */
    private static final int MSG_THRESHOLD = 60;

    /** 保留最近多少条消息不被压缩/卸载，保证近期上下文完整。 */
    private static final int LAST_KEEP = 20;

    private AgentMemoryFactory() {
    }

    /**
     * 构建调优后的 {@link AutoContextConfig}。
     */
    public static AutoContextConfig tunedConfig() {
        return AutoContextConfig.builder()
                .largePayloadThreshold(LARGE_PAYLOAD_THRESHOLD)
                .offloadSinglePreview(OFFLOAD_SINGLE_PREVIEW)
                .maxToken(MAX_TOKEN)
                .tokenRatio(TOKEN_RATIO)
                .msgThreshold(MSG_THRESHOLD)
                .lastKeep(LAST_KEEP)
                .build();
    }

    /**
     * 使用调优配置创建一个 {@link AutoContextMemory} 实例。
     *
     * @param model 用于历史摘要/压缩的模型（大负载卸载本身不消耗模型，仅摘要策略会用到）
     * @return 新的 {@link AutoContextMemory} 实例
     */
    public static AutoContextMemory create(Model model) {
        return new AutoContextMemory(tunedConfig(), model);
    }
}
