package com.gogo.travel.config;

import io.agentscope.core.embedding.dashscope.DashScopeTextEmbedding;
import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.integration.bailian.BailianConfig;
import io.agentscope.core.rag.integration.bailian.BailianKnowledge;
import io.agentscope.core.rag.knowledge.SimpleKnowledge;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.reader.ReaderInput;
import io.agentscope.core.rag.reader.WordReader;
import io.agentscope.core.rag.store.InMemoryStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import java.io.File;
import java.util.List;

/**
 * 景点 RAG 知识库装配配置。
 *
 * @author Hollis
 */
@Configuration
public class RagKnowledgeConfig {

    @Value("${agentscope.dashscope.api-key}")
    private String apiKey;

    @Value("${agentscope.bailian.access-key-id}")
    private String accessKeyId;

    @Value("${agentscope.bailian.access-key-secret}")
    private String accessKeySecret;

    @Value("${agentscope.bailian.workspace-id}")
    private String workspaceId;

    @Value("${agentscope.bailian.index-id}")
    private String indexId;

    @Bean(name = "attractionKnowledge")
    public Knowledge attractionKnowledge() {
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
        DashScopeTextEmbedding embedding = DashScopeTextEmbedding.builder()
                .modelName("text-embedding-v4")
                .apiKey(apiKey).build();

        SimpleKnowledge knowledge = SimpleKnowledge.builder()
                .embeddingStore(InMemoryStore.builder().build())
                .embeddingModel(embedding)
                .build();

        WordReader reader = new WordReader();
        try {
            File policyFile = policyResource.getFile();
            List<Document> docs = reader.read(ReaderInput.fromPath(policyFile.toPath())).block();
            knowledge.addDocuments(docs).block();
        } catch (Exception e) {
            // 资源缺失或解析失败时主动报错，提示开发者补齐语料
            throw new IllegalStateException(
                    "从 classpath 读取 [dataset/business_travel_policy.docx] 失败，请确认文件存在于 src/main/resources/dataset/ 目录下。", e);
        }

        return knowledge;
    }

    @Bean(name = "corporateTravelGuidelinesKnowledge")
    public Knowledge corporateTravelGuidelinesKnowledge(@Value("classpath:dataset/business_travel_guidelines.docx") Resource fileResource) {
        DashScopeTextEmbedding embedding = DashScopeTextEmbedding.builder()
                .modelName("text-embedding-v4")
                .apiKey(apiKey).build();

        SimpleKnowledge knowledge = SimpleKnowledge.builder()
                .embeddingStore(InMemoryStore.builder().build())
                .embeddingModel(embedding)
                .build();

        WordReader reader = new WordReader();
        try {
            File policyFile = fileResource.getFile();
            List<Document> docs = reader.read(ReaderInput.fromPath(policyFile.toPath())).block();
            knowledge.addDocuments(docs).block();
        } catch (Exception e) {
            // 资源缺失或解析失败时主动报错，提示开发者补齐语料
            throw new IllegalStateException(
                    "从 classpath 读取 [dataset/business_travel_guidelines.docx] 失败，请确认文件存在于 src/main/resources/dataset/ 目录下。", e);
        }

        return knowledge;
    }


}
