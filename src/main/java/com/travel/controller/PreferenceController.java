package com.travel.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.alibaba.fastjson2.JSON;
import com.travel.agent.memory.TravelPreferenceLongTermMemoryFactory;
import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 用户差旅偏好管理接口。
 *
 * <p>提供预定义的差旅偏好选项供用户勾选，保存后写入百炼长期记忆，
 * 供 Agent 在规划行程时通过 {@code retrieve_from_memory} 召回。</p>
 *
 * <p>读取时直接走 {@link com.travel.agent.memory.TravelPreferenceLongTermMemory#retrieve}，
 * 其内部已有 Redis 缓存层（{@link com.travel.agent.memory.TravelPreferenceMemoryCache}），
 * 无需额外存储。</p>
 *
 * @author Hollis
 */
@RestController
@RequestMapping("/api/preferences")
@CrossOrigin(origins = "*")
public class PreferenceController {

    private static final Logger logger = LoggerFactory.getLogger(PreferenceController.class);

    @Autowired
    private TravelPreferenceLongTermMemoryFactory memoryFactory;

    @Autowired
    @Qualifier("fastModel")
    private Model fastModel;

    /**
     * 获取所有可供选择的差旅偏好项（分类 + 选项列表）。
     */
    @GetMapping("/options")
    @SaCheckLogin
    public List<Map<String, Object>> getPreferenceOptions() {
        return buildPreferenceOptions();
    }

    /**
     * 获取当前用户已保存的偏好（从长期记忆召回，已有 Redis 缓存层加速）。
     *
     * <p>召回自然语言摘要后，通过 LLM 解析为结构化 JSON，供前端表单回显。</p>
     */
    @GetMapping
    @SaCheckLogin
    public ResponseEntity<Map<String, Object>> getUserPreferences() {
        String userId = StpUtil.getLoginIdAsString();
        String memorySummary = retrieveFromLongTermMemory(userId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", userId);
        if (memorySummary != null && !memorySummary.isBlank()) {
            result.put("memorySummary", memorySummary);
            // 通过 LLM 将自然语言解析为结构化偏好 JSON
            Map<String, Object> parsed = parseMemoryToPreferences(memorySummary);
            if (parsed != null && !parsed.isEmpty()) {
                result.put("preferences", parsed);
            }
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 保存用户偏好设置：格式化为自然语言并记录到长期记忆。
     */
    @PostMapping
    @SaCheckLogin
    public ResponseEntity<Map<String, Object>> saveUserPreferences(@RequestBody Map<String, Object> preferences) {
        String userId = StpUtil.getLoginIdAsString();
        logger.info("[PreferenceController] 保存用户偏好 userId={}, keys={}", userId, preferences.keySet());

        recordToLongTermMemory(userId, preferences);

        return ResponseEntity.ok(Map.of("saved", true));
    }

    /**
     * 通过 LLM 将自然语言偏好描述解析为结构化 JSON。
     *
     * <p>使用 fastModel（qwen-flash）完成轻量级抽取任务，
     * 将百炼 retrieve 返回的自然语言映射回预定义选项值。</p>
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMemoryToPreferences(String memorySummary) {
        try {
            String systemPrompt = buildParsePrompt();
            List<Msg> messages = List.of(
                    Msg.builder()
                            .role(MsgRole.SYSTEM).name("system")
                            .content(TextBlock.builder().text(systemPrompt).build())
                            .build(),
                    Msg.builder()
                            .role(MsgRole.USER).name("user")
                            .content(TextBlock.builder().text(memorySummary).build())
                            .build()
            );
            List<ChatResponse> responses = fastModel.stream(messages, null, null).collectList().block();
            String jsonText = extractText(responses);
            // 去除可能的 markdown 代码块标记
            jsonText = jsonText.strip();
            if (jsonText.startsWith("```")) {
                jsonText = jsonText.replaceFirst("```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
            }
            logger.debug("[PreferenceController] LLM 解析结果: {}", jsonText);
            return JSON.parseObject(jsonText, Map.class);
        } catch (Exception e) {
            logger.warn("[PreferenceController] LLM 解析偏好失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 构建 LLM 解析提示词，包含所有合法选项定义。
     */
    private String buildParsePrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个 JSON 提取器。根据用户的自然语言偏好描述，提取出结构化的差旅偏好 JSON。\n");
        sb.append("\n规则：\n");
        sb.append("1. 只输出纯 JSON，不要任何解释\n");
        sb.append("2. key 必须是下面定义的字段名\n");
        sb.append("3. single 类型的值为字符串，multi 类型的值为字符串数组\n");
        sb.append("4. 值必须从对应的 options 中选取，尽量模糊匹配（如'国航'匹配'国航(CA)'）\n");
        sb.append("5. 如果某个字段在描述中未提及，不要输出该字段\n");
        sb.append("6. 如果描述中的值无法匹配任何选项，跳过该字段\n");
        sb.append("\n可用字段定义：\n");

        List<Map<String, Object>> categories = buildPreferenceOptions();
        for (Map<String, Object> cat : categories) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) cat.get("items");
            for (Map<String, Object> item : items) {
                sb.append("- ").append(item.get("key"))
                        .append(" (").append(item.get("type")).append("): ")
                        .append(item.get("options")).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * 将流式 ChatResponse 中的所有 TextBlock 拼接为完整字符串。
     */
    private static String extractText(List<ChatResponse> responses) {
        if (responses == null || responses.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ChatResponse response : responses) {
            List<ContentBlock> blocks = response.getContent();
            if (blocks == null) {
                continue;
            }
            for (ContentBlock block : blocks) {
                if (block instanceof TextBlock textBlock) {
                    sb.append(textBlock.getText());
                }
            }
        }
        return sb.toString();
    }

    /**
     * 从百炼长期记忆中召回用户差旅偏好摘要。
     * 已经被 {@link com.travel.agent.memory.TravelPreferenceMemoryCache} 缓存，30 分钟内不会重复调百炼。
     */
    private String retrieveFromLongTermMemory(String userId) {
        try {
            LongTermMemory memory = memoryFactory.create(userId);
            if (memory == null) {
                return null;
            }
            Msg queryMsg = Msg.builder()
                    .role(MsgRole.USER)
                    .name("user")
                    .content(TextBlock.builder()
                            .text("我的差旅偏好是什么？包括机票、酒店、火车、餐饮、交通等方面的偏好")
                            .build())
                    .build();
            String result = memory.retrieve(queryMsg).block();
            logger.info("[PreferenceController] 从长期记忆召回偏好 userId={}, length={}",
                    userId, result != null ? result.length() : 0);
            return result;
        } catch (Exception e) {
            logger.warn("[PreferenceController] 从长期记忆召回偏好失败 userId={}: {}", userId, e.getMessage());
            return null;
        }
    }

    /**
     * 将结构化偏好转为自然语言，通过百炼长期记忆 record 接口持久化。
     * record 成功后会自动失效 Redis 缓存，下次 retrieve 将拿到最新数据。
     */
    private void recordToLongTermMemory(String userId, Map<String, Object> preferences) {
        try {
            LongTermMemory memory = memoryFactory.create(userId);
            if (memory == null) {
                logger.warn("[PreferenceController] 长期记忆未配置，跳过记录 userId={}", userId);
                return;
            }
            String naturalLanguage = formatPreferencesAsText(preferences);
            Msg userMsg = Msg.builder()
                    .role(MsgRole.USER)
                    .name("user")
                    .content(TextBlock.builder()
                            .text("请记住我的差旅偏好设置：" + naturalLanguage)
                            .build())
                    .build();
            Msg assistantMsg = Msg.builder()
                    .role(MsgRole.ASSISTANT)
                    .name("assistant")
                    .content(TextBlock.builder()
                            .text("好的，我已记住您的差旅偏好：" + naturalLanguage)
                            .build())
                    .build();
            memory.record(List.of(userMsg, assistantMsg)).block();
            logger.info("[PreferenceController] 偏好已记录到长期记忆 userId={}", userId);
        } catch (Exception e) {
            logger.warn("[PreferenceController] 记录偏好到长期记忆失败 userId={}: {}", userId, e.getMessage());
        }
    }

    private String formatPreferencesAsText(Map<String, Object> preferences) {
        Map<String, String> labels = getFieldLabels();
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> entry : preferences.entrySet()) {
            String label = labels.getOrDefault(entry.getKey(), entry.getKey());
            Object value = entry.getValue();
            String valueStr;
            if (value instanceof List<?> list) {
                valueStr = String.join("、", list.stream().map(Object::toString).toList());
            } else {
                valueStr = value != null ? value.toString() : "";
            }
            if (!valueStr.isBlank()) {
                sb.append(label).append("：").append(valueStr).append("；");
            }
        }
        return sb.toString();
    }

    private Map<String, String> getFieldLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("flight_cabin", "机票舱位偏好");
        labels.put("flight_airline", "偏好航司");
        labels.put("flight_seat", "飞机座位偏好");
        labels.put("flight_time", "航班时间偏好");
        labels.put("flight_direct", "中转偏好");
        labels.put("hotel_star", "酒店星级偏好");
        labels.put("hotel_brand", "偏好酒店品牌");
        labels.put("hotel_room", "房型偏好");
        labels.put("hotel_floor", "楼层偏好");
        labels.put("hotel_location", "酒店位置偏好");
        labels.put("hotel_facilities", "酒店设施需求");
        labels.put("train_seat", "高铁/火车座位偏好");
        labels.put("train_time", "高铁出发时段偏好");
        labels.put("train_position", "高铁座位位置偏好");
        labels.put("transport", "市内交通偏好");
        labels.put("reimburse_priority", "费用敏感度");
        labels.put("schedule_priority", "时间安排偏好");
        labels.put("meal", "餐饮偏好");
        return labels;
    }

    private List<Map<String, Object>> buildPreferenceOptions() {
        List<Map<String, Object>> categories = new ArrayList<>();

        categories.add(Map.of(
                "category", "flight",
                "label", "机票偏好",
                "icon", "✈️",
                "items", List.of(
                        Map.of("key", "flight_cabin", "label", "舱位偏好", "type", "single",
                                "options", List.of("经济舱", "超级经济舱", "公务舱", "头等舱")),
                        Map.of("key", "flight_airline", "label", "偏好航司", "type", "multi",
                                "options", List.of("国航(CA)", "东航(MU)", "南航(CZ)", "海航(HU)", "厦航(MF)", "深航(ZH)", "川航(3U)", "春秋(9C)", "吉祥(HO)", "山航(SC)")),
                        Map.of("key", "flight_seat", "label", "座位位置", "type", "single",
                                "options", List.of("靠窗", "靠过道", "前排", "紧急出口排", "无偏好")),
                        Map.of("key", "flight_time", "label", "航班时间", "type", "single",
                                "options", List.of("早班(6:00-9:00)", "上午(9:00-12:00)", "下午(12:00-18:00)", "晚班(18:00-21:00)", "红眼航班也可以", "无偏好")),
                        Map.of("key", "flight_direct", "label", "中转偏好", "type", "single",
                                "options", List.of("只选直飞", "可接受一次中转", "价格优先不限中转", "无偏好"))
                )
        ));

        categories.add(Map.of(
                "category", "hotel",
                "label", "酒店偏好",
                "icon", "🏨",
                "items", List.of(
                        Map.of("key", "hotel_star", "label", "星级偏好", "type", "single",
                                "options", List.of("经济型", "舒适型(三星)", "高档型(四星)", "豪华型(五星)", "无偏好")),
                        Map.of("key", "hotel_brand", "label", "偏好品牌", "type", "multi",
                                "options", List.of("全季", "亚朵", "如家商旅", "汉庭", "维也纳", "桔子", "希尔顿", "万豪", "洲际", "凯悦", "香格里拉", "华住")),
                        Map.of("key", "hotel_room", "label", "房型偏好", "type", "single",
                                "options", List.of("大床房", "双床房", "无偏好")),
                        Map.of("key", "hotel_floor", "label", "楼层偏好", "type", "single",
                                "options", List.of("高楼层", "低楼层(方便出行)", "无偏好")),
                        Map.of("key", "hotel_location", "label", "位置偏好", "type", "multi",
                                "options", List.of("靠近办公/会议地点", "靠近地铁/交通枢纽", "靠近市中心", "安静环境", "有停车场")),
                        Map.of("key", "hotel_facilities", "label", "设施需求", "type", "multi",
                                "options", List.of("健身房", "早餐", "免费Wi-Fi", "商务中心", "洗衣服务", "接机服务"))
                )
        ));

        categories.add(Map.of(
                "category", "train",
                "label", "高铁/火车偏好",
                "icon", "🚄",
                "items", List.of(
                        Map.of("key", "train_seat", "label", "座位等级", "type", "single",
                                "options", List.of("二等座", "一等座", "商务座", "无偏好")),
                        Map.of("key", "train_time", "label", "出发时段", "type", "single",
                                "options", List.of("早班(6:00-9:00)", "上午(9:00-12:00)", "下午(12:00-18:00)", "晚班(18:00-21:00)", "无偏好")),
                        Map.of("key", "train_position", "label", "座位位置", "type", "single",
                                "options", List.of("靠窗", "靠过道", "无偏好"))
                )
        ));

        categories.add(Map.of(
                "category", "general",
                "label", "出行习惯",
                "icon", "🧳",
                "items", List.of(
                        Map.of("key", "transport", "label", "市内交通", "type", "single",
                                "options", List.of("地铁/公交优先", "打车优先", "自驾/租车", "无偏好")),
                        Map.of("key", "reimburse_priority", "label", "费用敏感度", "type", "single",
                                "options", List.of("价格优先(尽量省钱)", "性价比优先(合理范围选舒适)", "体验优先(预算内最舒适)", "无偏好")),
                        Map.of("key", "schedule_priority", "label", "时间安排", "type", "single",
                                "options", List.of("尽量当天往返", "提前一晚到达", "会议/办事结束当天返回", "灵活安排", "无偏好")),
                        Map.of("key", "meal", "label", "餐饮偏好", "type", "multi",
                                "options", List.of("无特殊要求", "清淡饮食", "素食", "清真", "无辣", "不含海鲜", "无麸质"))
                )
        ));

        return categories;
    }
}
