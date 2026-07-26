package com.travel.config;

import com.travel.agent.intent.IntentCategory;
import com.travel.agent.intent.IntentRecognitionResult;
import com.travel.agent.intent.IntentRuleMatcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Optional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link IntentRouterKnowledgeConfig#initSeedExamples()} L2 种子语料结构校验。
 *
 * <p>L2 走 DashScope embedding + Top-1 最近邻检索，需真实网络与 API-Key，无法离线做语义断言。
 * 这里做可离线运行的结构性护栏，防止语料退化导致 L2 命中率/精度下降。
 * 语料已外置到 {@code intent-seed.yml}，本测试直接读取该配置文件作为单一数据源：</p>
 * <ul>
 *   <li>覆盖全部 {@link IntentCategory}（含 UNKNOWN），每类样本数量充足；</li>
 *   <li>无空白样本、类内无重复；</li>
 *   <li>跨类无完全相同的样本——同一句挂到两个意图会让 Top-1 检索产生歧义。</li>
 * </ul>
 *
 * @author Hollis
 */
@DisplayName("L2 意图种子语料结构校验")
class IntentRouterSeedExamplesTest {

    /**
     * 每个意图至少应提供的样本数，保证 embedding 检索有足够语义覆盖。
     * 注：L2 只负责 L1 难命中的口语化表达，可用样本池比含关键词时小，
     * 故阈值取相对保守的 3。 */
    private static final int MIN_EXAMPLES_PER_CATEGORY = 3;

    @SuppressWarnings("unchecked")
    private Map<IntentCategory, List<String>> loadExamples() {
        Yaml yaml = new Yaml();
        try (InputStream in = getClass().getResourceAsStream("/intent-seed.yml")) {
            assertNotNull(in, "未找到 intent-seed.yml，请确认它位于 resources 根目录");
            Map<String, Object> root = yaml.load(in);
            Map<String, Object> intent = (Map<String, Object>) root.get("intent");
            Map<String, Object> router = (Map<String, Object>) intent.get("router");
            Map<String, List<String>> seed = (Map<String, List<String>>) router.get("seed-examples");

            Map<IntentCategory, List<String>> result = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> entry : seed.entrySet()) {
                result.put(IntentCategory.fromCode(entry.getKey()), entry.getValue());
            }
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("加载 intent-seed.yml 失败", e);
        }
    }

    @Test
    @DisplayName("覆盖全部意图且每类样本充足")
    void shouldCoverAllCategoriesWithEnoughExamples() {
        Map<IntentCategory, List<String>> examples = loadExamples();
        List<String> failures = new ArrayList<>();

        for (IntentCategory category : IntentCategory.values()) {
            List<String> list = examples.get(category);
            if (list == null || list.isEmpty()) {
                failures.add(category + " 缺少种子样本");
                continue;
            }
            if (list.size() < MIN_EXAMPLES_PER_CATEGORY) {
                failures.add(String.format("%s 样本数=%d，少于要求的 %d",
                        category, list.size(), MIN_EXAMPLES_PER_CATEGORY));
            }
        }
        assertTrue(failures.isEmpty(), report("意图覆盖/数量不足", failures));
    }

    @Test
    @DisplayName("样本无空白且类内不重复")
    void shouldHaveNoBlankOrIntraCategoryDuplicate() {
        Map<IntentCategory, List<String>> examples = loadExamples();
        List<String> failures = new ArrayList<>();

        for (Map.Entry<IntentCategory, List<String>> entry : examples.entrySet()) {
            List<String> seen = new ArrayList<>();
            for (String example : entry.getValue()) {
                if (example == null || example.isBlank()) {
                    failures.add(entry.getKey() + " 含空白样本");
                } else if (seen.contains(example)) {
                    failures.add(String.format("%s 类内重复样本「%s」", entry.getKey(), example));
                } else {
                    seen.add(example);
                }
            }
        }
        assertTrue(failures.isEmpty(), report("空白或类内重复", failures));
    }

    @Test
    @DisplayName("L2 样本不应与 L1 规则重叠（L1 能命中的 case L2 无需覆盖）")
    void shouldNotOverlapWithL1Rules() {
        Map<IntentCategory, List<String>> examples = loadExamples();
        IntentRuleMatcher matcher = new IntentRuleMatcher();
        List<String> failures = new ArrayList<>();

        for (Map.Entry<IntentCategory, List<String>> entry : examples.entrySet()) {
            for (String example : entry.getValue()) {
                Optional<IntentRecognitionResult> hit = matcher.match(example);
                if (hit.isPresent()) {
                    failures.add(String.format("%s 的样本「%s」已被 L1 命中(=%s)，属冗余，应从 L2 移除",
                            entry.getKey(), example, hit.get().getPrimary()));
                }
            }
        }
        assertTrue(failures.isEmpty(), report("L2 与 L1 重叠", failures));
    }

    @Test
    @DisplayName("跨意图不存在完全相同的样本")
    void shouldHaveNoCrossCategoryDuplicate() {
        Map<IntentCategory, List<String>> examples = loadExamples();
        Map<String, IntentCategory> owner = new HashMap<>();
        List<String> failures = new ArrayList<>();

        for (Map.Entry<IntentCategory, List<String>> entry : examples.entrySet()) {
            for (String example : entry.getValue()) {
                IntentCategory prev = owner.putIfAbsent(example, entry.getKey());
                if (prev != null && prev != entry.getKey()) {
                    failures.add(String.format("样本「%s」同时属于 %s 与 %s", example, prev, entry.getKey()));
                }
            }
        }
        assertTrue(failures.isEmpty(), report("跨意图样本冲突", failures));
    }

    private static String report(String title, List<String> failures) {
        StringBuilder sb = new StringBuilder();
        sb.append(title).append("，共 ").append(failures.size()).append(" 条：\n");
        for (String f : failures) {
            sb.append("  - ").append(f).append('\n');
        }
        return sb.toString();
    }
}
