package com.travel.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * L2 意图识别向量库种子语料配置。
 *
 * <p>语料内容外置到 {@code intent-seed.yml}，通过本类绑定到内存。
 * key 为 {@link com.travel.agent.intent.IntentCategory} 的 code，
 * value 为该意图的高频口语问句列表。</p>
 *
 * @author Hollis
 */
@Component
@ConfigurationProperties(prefix = "intent.router")
public class IntentSeedExamplesProperties {

    /** 各意图的种子问句，key=意图 code，保持 YAML 中的声明顺序。 */
    private Map<String, List<String>> seedExamples = new LinkedHashMap<>();

    public Map<String, List<String>> getSeedExamples() {
        return seedExamples;
    }

    public void setSeedExamples(Map<String, List<String>> seedExamples) {
        this.seedExamples = seedExamples;
    }
}
