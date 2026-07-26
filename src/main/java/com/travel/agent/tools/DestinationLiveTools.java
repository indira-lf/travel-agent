package com.travel.agent.tools;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import jakarta.annotation.PostConstruct;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 出差目的地实时联网查询工具集：天气查询 + 目的地资讯查询。
 *
 * <p><b>工具列表：</b>
 * <ul>
 *   <li>{@code query_weather}：调用 <a href="https://wttr.in">wttr.in</a> 免费公开 API，
 *       获取实时天气及 3 天逐日/逐小时预报。支持按日期查询（今天起未来 2 天内），
 *       <b>无需 API Key</b>，开箱即用。</li>
 *   <li>{@code query_destination_news}：调用 <a href="https://newsdata.io">NewsData.io</a>
 *       API 查询目的地最新资讯（交通管制、重大活动、突发事件等）。
 *       免费额度 200 次/天，需在 {@code app.news-api-key} 中配置 API Key。
 *       <b>未配置时优雅降级</b>，返回提示信息而非报错，不影响 Agent 正常运行。</li>
 * </ul>
 *
 * <p><b>错误处理原则：</b>网络请求失败时捕获异常并返回结构化错误 JSON，
 * 确保 Agent 仍能收到可读响应并向用户说明情况，而非抛出异常中断对话。
 *
 * @author Hollis
 */
@Component
public class DestinationLiveTools {

    private static final Logger logger = LoggerFactory.getLogger(DestinationLiveTools.class);

    /** wttr.in：免费天气 API，无需 API Key，直接按城市名查询 */
    private static final String WEATHER_API_BASE = "https://wttr.in/";

    /** wttr.in 支持的最大预报天数（今天起算） */
    private static final int WEATHER_FORECAST_DAYS = 2;

    /** 天气查询 HTTP 请求超时（秒） */
    private static final int WEATHER_REQUEST_TIMEOUT_SECONDS = 8;

    /** 新闻查询每次返回最大条数 */
    private static final String NEWS_PAGE_SIZE = "5";
    /** 新闻查询 HTTP 请求超时（秒） */
    private static final int NEWS_REQUEST_TIMEOUT_SECONDS = 10;

    @Autowired
    private WebClient.Builder webClientBuilder;
    private WebClient webClient;

    /**
     * NewsData.io API Key。
     * 通过 {@code app.news-api-key} 配置；默认为空，未配置时新闻查询降级返回提示信息。
     * 免费注册地址：<a href="https://newsdata.io">https://newsdata.io</a>
     */
    @Value("${app.news-api-key:}")
    private String newsApiKey;

    @PostConstruct
    public void init() {
        this.webClient = webClientBuilder.build();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 天气查询（wttr.in 优先，免费，无需 Key；超出 3 天范围时提示改用高德 MCP）
    // ──────────────────────────────────────────────────────────────────────────

    @Tool(name = "query_weather",
          description = "联网查询目的地城市的天气信息（首选工具，免费）。"
                  + "支持今天及未来 2 天（共 3 天）的天气预报；"
                  + "若 date 超出此范围，工具会返回 beyond_range=true，Agent 应改用 weather-mcp 工具查询。"
                  + "不指定 date 则返回当前天气 + 未来 3 天全量预报。"
                  + "每日预报包含最高/最低温度和逐 3 小时天气（温度、降雨概率、风速、状况描述）。"
                  + "支持中文城市名（如 北京、上海）和英文城市名（如 Tokyo、Paris）。")
    public String queryWeather(
            @ToolParam(name = "city", description = "目标城市，如 北京、上海、杭州、Tokyo、Paris") String city,
            @ToolParam(name = "date",
                       description = "查询日期，YYYY-MM-DD 格式，如 2026-07-15；"
                               + "仅支持今天及未来 2 天（wttr.in 限制），超出范围会提示改用 weather-mcp；"
                               + "可选，不填则返回全量 3 天预报",
                       required = false) String date) {

        logger.info("[TOOL][query_weather] city={}, date={}", city, date);

        // ── 日期范围预检：超出 today+2 则直接返回 beyond_range 信号 ──────────────
        LocalDate targetDate = parseDate(date);
        if (targetDate != null) {
            LocalDate maxDate = LocalDate.now().plusDays(WEATHER_FORECAST_DAYS);
            if (targetDate.isAfter(maxDate)) {
                logger.info("[TOOL][query_weather] 日期 {} 超出 wttr.in 范围，提示使用 maps_weather", date);
                JSONObject beyond = new JSONObject();
                beyond.put("city", city);
                beyond.put("date", date);
                beyond.put("beyond_range", true);
                beyond.put("message",
                        date + " 超出 wttr.in 支持范围（仅今天起 3 天内）。"
                                + "请立即改用 maps_weather 工具查询该日期的天气预报。");
                return JSON.toJSONString(beyond);
            }
        }

        try {
            // wttr.in j1 格式：current_condition（实时）+ weather（3天逐日逐小时预报）
            String encoded = URLEncoder.encode(city, StandardCharsets.UTF_8);
            String responseBody = webClient.get()
                    .uri(WEATHER_API_BASE + encoded + "?format=j1")
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(WEATHER_REQUEST_TIMEOUT_SECONDS))
                    .block();

            JSONObject raw = JSON.parseObject(responseBody);
            JSONObject result = new JSONObject();
            result.put("city", city);
            result.put("source", "wttr.in");
            result.put("queryDate", date != null && !date.isBlank() ? date : "today");

            // 解析目标日期（用于过滤预报）—— 此处 targetDate 已在前置检查中解析过

            // ── 当前实况（仅在未指定日期，或指定日期为今天时返回）────────────────
            boolean isToday = (targetDate == null || targetDate.equals(LocalDate.now()));
            if (isToday) {
                JSONArray currentArr = raw.getJSONArray("current_condition");
                if (currentArr != null && !currentArr.isEmpty()) {
                    JSONObject cur = currentArr.getJSONObject(0);
                    JSONObject current = new JSONObject();
                    current.put("tempC", cur.getString("temp_C") + "°C");
                    current.put("feelsLikeC", cur.getString("FeelsLikeC") + "°C");
                    current.put("humidity", cur.getString("humidity") + "%");
                    current.put("windKmph", cur.getString("windspeedKmph") + " km/h");
                    JSONArray descArr = cur.getJSONArray("weatherDesc");
                    if (descArr != null && !descArr.isEmpty()) {
                        current.put("description", descArr.getJSONObject(0).getString("value"));
                    }
                    result.put("current", current);
                }
            }

            // ── 逐日预报（指定日期时只返回该天，否则返回全部 3 天）────────────────
            JSONArray forecastArr = raw.getJSONArray("weather");
            if (forecastArr != null && !forecastArr.isEmpty()) {
                List<JSONObject> forecast = new ArrayList<>();
                for (int i = 0; i < forecastArr.size(); i++) {
                    JSONObject day = forecastArr.getJSONObject(i);
                    String dayDateStr = day.getString("date");

                    // 指定了日期时，跳过不匹配的天
                    if (targetDate != null && !targetDate.equals(LocalDate.now())) {
                        LocalDate dayDate = parseDate(dayDateStr);
                        if (dayDate == null || !dayDate.equals(targetDate)) {
                            continue;
                        }
                    }

                    JSONObject f = new JSONObject();
                    f.put("date", dayDateStr);
                    f.put("maxTempC", day.getString("maxtempC") + "°C");
                    f.put("minTempC", day.getString("mintempC") + "°C");

                    // 逐 3 小时预报（wttr.in 共 8 个时段：00/03/06/09/12/15/18/21）
                    JSONArray hourly = day.getJSONArray("hourly");
                    if (hourly != null && !hourly.isEmpty()) {
                        List<JSONObject> hourlyList = new ArrayList<>();
                        for (int h = 0; h < hourly.size(); h++) {
                            JSONObject slot = hourly.getJSONObject(h);
                            JSONObject hItem = new JSONObject();
                            // time 字段为 "0"~"2100"，转成 HH:mm 格式
                            hItem.put("time", formatHourlyTime(slot.getString("time")));
                            hItem.put("tempC", slot.getString("tempC") + "°C");
                            hItem.put("windKmph", slot.getString("windspeedKmph") + " km/h");
                            hItem.put("chanceOfRain", slot.getString("chanceofrain") + "%");
                            JSONArray desc = slot.getJSONArray("weatherDesc");
                            if (desc != null && !desc.isEmpty()) {
                                hItem.put("description", desc.getJSONObject(0).getString("value"));
                            }
                            hourlyList.add(hItem);
                        }
                        f.put("hourly", hourlyList);
                    }
                    forecast.add(f);
                }

                if (forecast.isEmpty() && targetDate != null) {
                    // 不应到达这里（已在前置检查中拦截），保留作保险措施
                    result.put("beyond_range", true);
                    result.put("message",
                            date + " 超出 wttr.in 范围，请改用 maps_weather 工具查询。");
                } else {
                    result.put("forecast", forecast);
                }
            }

            logger.info("[TOOL][query_weather] city={}, date={} success", city, date);
            return JSON.toJSONString(result);

        } catch (Exception e) {
            logger.warn("[TOOL][query_weather] city={}, date={} failed: {}", city, date, e.getMessage());
            JSONObject err = new JSONObject();
            err.put("city", city);
            err.put("error", "天气查询暂时不可用，请告知用户稍后重试");
            err.put("detail", e.getMessage());
            return JSON.toJSONString(err);
        }
    }

    /**
     * 解析日期字符串（YYYY-MM-DD），失败时返回 null。
     */
    private LocalDate parseDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(dateStr.trim(), DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException e) {
            logger.warn("[query_weather] 日期格式无法解析：{}", dateStr);
            return null;
        }
    }

    /**
     * wttr.in hourly.time 字段为 "0"~"2100" 的字符串，转成 HH:mm 格式。
     * 如 "0" → "00:00"，"600" → "06:00"，"1200" → "12:00"。
     */
    private String formatHourlyTime(String time) {
        if (time == null || time.isBlank()) {
            return "";
        }
        int t = Integer.parseInt(time);
        return String.format("%02d:%02d", t / 100, t % 100);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 目的地资讯查询（NewsData.io，需配置 app.news-api-key）
    // ──────────────────────────────────────────────────────────────────────────

    @Tool(name = "query_destination_news",
          description = "联网查询出差目的地的最新资讯，辅助出行规划与风险评估。\n"
                  + "可查询主题（topic 参数）：\n"
                  + "- traffic：交通管制、道路封閉、限行限号\n"
                  + "- event：重大展会、会议、赛事、演艺\n"
                  + "- safety：治安状况、示威抚乱、突发事件\n"
                  + "- flight：航班延误、机场动态、天气取消\n"
                  + "- hotel：酒店旺季预定紧张期、封闭消息\n"
                  + "- policy：入境新规、签证政策变动\n"
                  + "- general：综合资讯（默认）\n")
    public String queryDestinationNews(
            @ToolParam(name = "city", description = "目标城市，如 北京、上海、杭州、深圳") String city,
            @ToolParam(name = "topic",
                       description = "关注主题：traffic（交通管制）/ event（活动展览）/ safety（安全状况）/ flight（航班动态）/ hotel（住宿资讯）/ policy（政策法规）/ general（综合资讯），可选",
                       required = false) String topic) {

        logger.info("[TOOL][query_destination_news] city={}, topic={}", city, topic);

        // ── 未配置 API Key：优雅降级，不抛异常 ───────────────────────────────
        if (newsApiKey == null || newsApiKey.isBlank()) {
            logger.warn("[TOOL][query_destination_news] app.news-api-key 未配置，跳过新闻查询");
            JSONObject placeholder = new JSONObject();
            placeholder.put("city", city);
            placeholder.put("available", false);
            placeholder.put("note",
                    "新闻查询功能需配置 app.news-api-key（免费注册：https://newsdata.io），当前未启用。"
                            + "如需获取目的地资讯，建议引导用户自行查阅当地官方网站或新闻媒体。");
            return JSON.toJSONString(placeholder);
        }

        try {
            // 拼接查询词：城市 + 主题（如 "北京 交通"）
            String query = city + (topic != null && !topic.isBlank() ? " " + topic : "");

            String responseBody = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .scheme("https")
                            .host("newsdata.io")
                            .path("/api/1/news")
                            .queryParam("apikey", newsApiKey)
                            .queryParam("q", query)
                            .queryParam("language", "zh")   // 中文新闻
                            .queryParam("size", NEWS_PAGE_SIZE)        // 最多返回条数
                            .build())
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(NEWS_REQUEST_TIMEOUT_SECONDS))
                    .block();

            JSONObject raw = JSON.parseObject(responseBody);
            JSONArray articles = raw.getJSONArray("results");

            JSONObject result = new JSONObject();
            result.put("city", city);
            result.put("topic", topic);
            result.put("source", "newsdata.io");
            result.put("available", true);

            if (articles == null || articles.isEmpty()) {
                result.put("news", List.of());
                result.put("note", "暂未检索到 " + city + " 的相关资讯");
            } else {
                List<JSONObject> newsList = new ArrayList<>();
                for (int i = 0; i < articles.size(); i++) {
                    JSONObject article = articles.getJSONObject(i);
                    JSONObject item = new JSONObject();
                    item.put("title", article.getString("title"));
                    item.put("description", article.getString("description"));
                    item.put("pubDate", article.getString("pubDate"));
                    item.put("source", article.getString("source_id"));
                    newsList.add(item);
                }
                result.put("news", newsList);
                result.put("count", newsList.size());
            }

            logger.info("[TOOL][query_destination_news] city={} done, count={}",
                    city, articles != null ? articles.size() : 0);
            return JSON.toJSONString(result);

        } catch (Exception e) {
            logger.warn("[TOOL][query_destination_news] city={} failed: {}", city, e.getMessage());
            JSONObject err = new JSONObject();
            err.put("city", city);
            err.put("available", false);
            err.put("error", "新闻查询请求失败，请告知用户稍后重试");
            err.put("detail", e.getMessage());
            return JSON.toJSONString(err);
        }
    }
}
