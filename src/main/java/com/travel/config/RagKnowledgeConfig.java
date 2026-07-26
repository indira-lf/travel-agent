package com.travel.config;

import io.agentscope.core.embedding.dashscope.DashScopeTextEmbedding;
import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.integration.bailian.BailianConfig;
import io.agentscope.core.rag.integration.bailian.BailianKnowledge;
import io.agentscope.core.rag.knowledge.SimpleKnowledge;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.reader.ReaderInput;
import io.agentscope.core.rag.reader.WordReader;
import io.agentscope.core.rag.store.InMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.util.StringUtils;

import java.io.File;
import java.util.List;

/**
 * 景点 RAG 知识库装配配置。
 *
 * @author Hollis
 */
@Configuration
public class RagKnowledgeConfig {

    private static final Logger logger = LoggerFactory.getLogger(RagKnowledgeConfig.class);

    @Value("${agentscope.dashscope.api-key}")
    private String apiKey;

    @Value("${agentscope.bailian.access-key-id:}")
    private String accessKeyId;

    @Value("${agentscope.bailian.access-key-secret:}")
    private String accessKeySecret;

    @Value("${agentscope.bailian.workspace-id:}")
    private String workspaceId;

    @Value("${agentscope.bailian.index-id:}")
    private String indexId;

    /**
     * 景点 RAG 知识库（百炼）。
     *
     * <p>仅在完整配置了百炼凭证（{@code access-key-id} / {@code access-key-secret} /
     * {@code workspace-id} / {@code index-id}）时才装配；任一缺失则返回 {@code null}，
     * {@code InfoAgent} 以可选注入方式跳过景点 RAG，应用仍可正常启动。
     * 与 {@code WeatherMcpConfig}/{@code OriznVisaMcpConfig} 的优雅降级模式一致。</p>
     */
    @Bean(name = "attractionKnowledge")
    public Knowledge attractionKnowledge() {
        if (!StringUtils.hasText(accessKeyId) || !StringUtils.hasText(accessKeySecret)
                || !StringUtils.hasText(workspaceId) || !StringUtils.hasText(indexId)) {
            logger.warn("[RAG] 百炼知识库凭证未配置完整（access-key-id/access-key-secret/workspace-id/index-id），"
                    + "跳过景点 RAG 装配，InfoAgent 将在无景点知识库的情况下运行");
            return null;
        }

        BailianConfig config =
                BailianConfig.builder()
                        .accessKeyId(accessKeyId)
                        .accessKeySecret(accessKeySecret)
                        .workspaceId(workspaceId)
                        .build();

        return BailianKnowledge.builder()
                .config(config)
                .indexId(indexId)
                .build();
    }

    @Bean(name = "corporateTravelPolicyKnowledge")
    public Knowledge corporateTravelPolicyKnowledge(@Value("classpath:dataset/business_travel_policy.docx") Resource policyResource) {
        return buildDocxKnowledge(policyResource, "corporateTravelPolicyKnowledge", "dataset/business_travel_policy.docx");
    }

    @Bean(name = "corporateTravelGuidelinesKnowledge")
    public Knowledge corporateTravelGuidelinesKnowledge(@Value("classpath:dataset/business_travel_guidelines.docx") Resource fileResource) {
        return buildDocxKnowledge(fileResource, "corporateTravelGuidelinesKnowledge", "dataset/business_travel_guidelines.docx");
    }

    /**
     * 构建基于本地 docx + DashScope embedding 的 {@link SimpleKnowledge}。
     *
     * <p>启动期会读取 docx 并批量 embedding 写入 InMemoryStore。任一环节失败
     * （DashScope API Key 未配置/失效、docx 缺失或解析失败）时返回 {@code null}，
     * {@code InfoAgent} 以可选注入方式跳过该知识库，应用仍可启动。
     * 与 {@link #attractionKnowledge()} 的优雅降级模式一致。</p>
     */
    private Knowledge buildDocxKnowledge(Resource resource, String beanName, String displayName) {
        if (!StringUtils.hasText(apiKey)) {
            logger.warn("[RAG] 未配置 agentscope.dashscope.api-key，跳过 {} 装配（{}）", beanName, displayName);
            return null;
        }

        DashScopeTextEmbedding embedding = DashScopeTextEmbedding.builder()
                .modelName("text-embedding-v4")
                .apiKey(apiKey).build();

        SimpleKnowledge knowledge = SimpleKnowledge.builder()
                .embeddingStore(InMemoryStore.builder().build())
                .embeddingModel(embedding)
                .build();

        WordReader reader = new WordReader();
        try {
            File file = resource.getFile();
            List<Document> docs = reader.read(ReaderInput.fromPath(file.toPath())).block();
            knowledge.addDocuments(docs).block();
        } catch (Exception e) {
            // 未配置/失效的 DashScope Key、资源缺失或解析失败时优雅降级，不阻塞启动
            logger.warn("[RAG] 装配 {} 失败（{}），已跳过。原因：{}", beanName, displayName, e.getMessage());
            return null;
        }

        return knowledge;
    }


}
