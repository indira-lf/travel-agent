package com.travel.agent.common;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 将各技能（flyai、rolling-go-hotel、tuniu-cli、flight-manager）返回的搜索结果，
 * 统一归一化为前端可用的 TravelData 格式：
 * <pre>
 * {
 *   "type": "flight" | "hotel" | "train",
 *   "items": [ ... ]
 * }
 * </pre>
 *
 * @author Hollis
 */
public class TravelDataNormalizer {

    private static final Logger logger = LoggerFactory.getLogger(TravelDataNormalizer.class);

    private static final Set<String> ARRAY_KEYS = Set.of(
            "flightList", "flights", "flightInfos",
            "hotelList", "hotels", "hotelInformationList",
            "trainList", "trains",
            "itemList", "items", "list", "data"
    );

    /** tuniu 火车票各席别价格字段 -> 座席中文名，用于展示最低价对应的席别。 */
    private static final Map<String, String> TRAIN_SEAT_NAMES = Map.ofEntries(
            Map.entry("swzPrice", "商务座"),
            Map.entry("tdzPrice", "特等座"),
            Map.entry("ydzPrice", "一等座"),
            Map.entry("edzPrice", "二等座"),
            Map.entry("rzPrice", "软座"),
            Map.entry("yzPrice", "硬座"),
            Map.entry("gjrwPrice", "国际软卧"),
            Map.entry("rwPrice", "软卧"),
            Map.entry("ywPrice", "硬卧"),
            Map.entry("dwPrice", "动卧"),
            Map.entry("ydwPrice", "一等卧"),
            Map.entry("edwPrice", "二等卧"),
            Map.entry("wzPrice", "无座")
    );

    /**
     * 尝试将任意工具输出文本归一化为统一的 travel_data 结构。
     *
     * @return 归一化后的 JSONObject（包含 type/items），无法识别时返回 null
     */
    public JSONObject normalize(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return null;
        }
        JSONObject root = parseRoot(rawText);
        if (root == null) {
            return null;
        }
        return normalizeRoot(root);
    }

    /**
     * 对已解析的根 JSON 对象执行归一化流程。
     * <p>入口会先解包 MCP 内容块包装（如 tuniu-cli 的 {result:{content:[{type:text,text:...}]}}），
     * 从内容块的 text 中还原真正的业务 JSON，避免把内容块本身误当作业务条目展示。
     */
    private JSONObject normalizeRoot(JSONObject root) {
        if (root == null) {
            return null;
        }
        try {
            // 0. 若为 MCP 内容块包装，先解包出内部业务 JSON 并递归归一化
            JSONObject unwrapped = unwrapContentWrapper(root);
            if (unwrapped != null) {
                return normalizeRoot(unwrapped);
            }

            // 1. rolling-go 酒店：根层级即为酒店列表
            JSONArray rollingGoHotels = root.getJSONArray("hotelInformationList");
            if (rollingGoHotels != null && !rollingGoHotels.isEmpty()) {
                return buildResult("hotel", mapRollingGoHotels(rollingGoHotels));
            }

            // 2. tuniu-cli：根层级通常直接包含 flights/hotels/trains 数组
            JSONObject tuniu = tryNormalizeTuniu(root);
            if (tuniu != null) {
                return tuniu;
            }

            // 3. flyai：{ data: { itemList: [...] } }
            JSONObject flyai = tryNormalizeFlyai(root);
            if (flyai != null) {
                return flyai;
            }

            // 4. flight-manager 等 MCP JSON-RPC：result.structuredContent.data
            JSONObject flightManager = tryNormalizeMcpResult(root);
            if (flightManager != null) {
                return flightManager;
            }

            // 5. 兜底：扫描任意可能的数组字段
            return tryNormalizeGeneric(root);
        } catch (Exception e) {
            logger.debug("[TRAVEL_DATA] 归一化失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 识别并解包 MCP 内容块包装结构，返回内容块 text 中承载的业务 JSON 对象；
     * 若当前对象不是内容块包装，则返回 null。
     * <p>兼容以下形态：
     * <pre>
     * { "result": { "content": [ {"type":"text","text":"&lt;业务JSON&gt;"} ] } }
     * { "content": [ {"type":"text","text":"&lt;业务JSON&gt;"} ] }
     * { "result": { "structuredContent": { "content": [ ... ] } } }
     * </pre>
     */
    private JSONObject unwrapContentWrapper(JSONObject root) {
        JSONObject fromRoot = unwrapContentBlocks(root.getJSONArray("content"));
        if (fromRoot != null) {
            return fromRoot;
        }
        JSONObject result = root.getJSONObject("result");
        if (result != null) {
            JSONObject fromResult = unwrapContentBlocks(result.getJSONArray("content"));
            if (fromResult != null) {
                return fromResult;
            }
            JSONObject structured = result.getJSONObject("structuredContent");
            if (structured != null) {
                JSONObject fromStructured = unwrapContentBlocks(structured.getJSONArray("content"));
                if (fromStructured != null) {
                    return fromStructured;
                }
            }
        }
        return null;
    }

    /**
     * 若给定数组是 MCP 内容块数组（元素形如 {"type":"text","text":"..."}），
     * 提取并拼接其中的 text 字段，解析为业务 JSON 对象返回；否则返回 null。
     */
    private JSONObject unwrapContentBlocks(JSONArray arr) {
        if (arr == null || arr.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.size(); i++) {
            JSONObject block = arr.getJSONObject(i);
            if (!isContentBlock(block)) {
                return null;
            }
            String text = block.getString("text");
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(text);
        }
        return parseRoot(sb.toString());
    }

    /**
     * 判断对象是否为 MCP 文本内容块：type 为 text 且携带 text 字段。
     */
    private boolean isContentBlock(JSONObject obj) {
        if (obj == null) {
            return false;
        }
        return "text".equals(obj.getString("type")) && obj.getString("text") != null;
    }

    // ------------------------------------------------------------
    // 解析与识别
    // ------------------------------------------------------------

    private JSONObject parseRoot(String rawText) {
        String trimmed = rawText.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        // 1. 直接解析对象或数组
        try {
            return JSON.parseObject(trimmed);
        } catch (Exception ignored) {
        }
        try {
            JSONArray arr = JSON.parseArray(trimmed);
            JSONObject wrap = new JSONObject();
            wrap.put("data", arr);
            return wrap;
        } catch (Exception ignored) {
        }
        // 2. 从文本中提取顶层 JSON 对象/数组（支持 markdown 代码块、多个 JSON 混合）
        List<String> candidates = extractJsonCandidates(trimmed);
        for (String candidate : candidates) {
            try {
                return JSON.parseObject(candidate);
            } catch (Exception ignored) {
            }
            try {
                JSONArray arr = JSON.parseArray(candidate);
                JSONObject wrap = new JSONObject();
                wrap.put("data", arr);
                return wrap;
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /**
     * 使用括号计数从文本中提取所有顶层 JSON 对象/数组候选，按长度降序排列。
     */
    private List<String> extractJsonCandidates(String text) {
        List<String> candidates = new ArrayList<>();
        int n = text.length();
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (c == '{' || c == '[') {
                char open = c;
                char close = c == '{' ? '}' : ']';
                int depth = 0;
                int j = i;
                for (; j < n; j++) {
                    char ch = text.charAt(j);
                    if (ch == open) {
                        depth++;
                    } else if (ch == close) {
                        depth--;
                        if (depth == 0) {
                            break;
                        }
                    }
                }
                if (depth == 0) {
                    candidates.add(text.substring(i, j + 1));
                }
            }
        }
        // 优先尝试最长的候选（通常包含完整业务数据）
        candidates.sort((a, b) -> Integer.compare(b.length(), a.length()));
        return candidates;
    }

    private JSONObject tryNormalizeTuniu(JSONObject root) {
        // 途牛 CLI 查询结果通常在根对象中包含 flights/hotels/trains 等数组
        for (String key : List.of("flights", "hotels", "trains")) {
            JSONArray arr = root.getJSONArray(key);
            if (arr != null && !arr.isEmpty()) {
                // 复数转单数：flights->flight, hotels->hotel, trains->train
                String type = switch (key) {
                    case "flights" -> "flight";
                    case "hotels" -> "hotel";
                    case "trains" -> "train";
                    default -> "unknown";
                };
                return buildResult(type, normalizeItems(arr, type));
            }
        }
        return null;
    }

    private JSONObject tryNormalizeFlyai(JSONObject root) {
        // data 可能是数组（如 tuniu 火车票 {data:[...]}），此时不属于 flyai 结构，直接放行
        Object dataVal = root.get("data");
        if (!(dataVal instanceof JSONObject data)) {
            return null;
        }
        JSONArray itemList = data.getJSONArray("itemList");
        if (itemList == null || itemList.isEmpty()) {
            return null;
        }
        String type = detectFlyaiType(itemList.getJSONObject(0));
        if (type == null) {
            return null;
        }
        return buildResult(type, normalizeItems(itemList, type));
    }

    private JSONObject tryNormalizeMcpResult(JSONObject root) {
        JSONObject result = root.getJSONObject("result");
        if (result == null) {
            return null;
        }

        // 1. 有些 MCP 结果直接是业务对象，例如 tuniu-cli：result 下挂 flights/trains/hotels
        for (String key : List.of("flights", "trains", "hotels",
                "flightList", "trainList", "hotelList", "flightInfos")) {
            JSONArray arr = result.getJSONArray(key);
            if (arr != null && !arr.isEmpty()) {
                String type = arrayKeyToType(key);
                if (type == null) {
                    type = detectGenericTypeByItem(arr.getJSONObject(0));
                }
                return buildResult(type, normalizeItems(arr, type));
            }
        }

        // 2. 标准 MCP 结构：result.structuredContent.data / result.data
        JSONObject structuredContent = result.getJSONObject("structuredContent");
        Object payload = null;
        if (structuredContent != null) {
            payload = structuredContent.get("data");
            if (payload == null) {
                payload = structuredContent.get("content");
            }
        }
        if (payload == null) {
            payload = result.get("data");
        }
        if (payload == null) {
            payload = result.get("content");
        }

        // payload 可能是 JSON 字符串，先解析
        if (payload instanceof String s && !s.isBlank()) {
            JSONObject parsed = parseRoot(s);
            if (parsed != null) {
                payload = parsed;
            }
        }

        if (payload instanceof JSONObject parsedObj) {
            // 在解析后的对象内部查找已知数组
            for (String key : ARRAY_KEYS) {
                JSONArray arr = parsedObj.getJSONArray(key);
                if (arr != null && !arr.isEmpty()) {
                    String type = arrayKeyToType(key);
                    if (type == null) {
                        type = detectGenericTypeByItem(arr.getJSONObject(0));
                    }
                    return buildResult(type, normalizeItems(arr, type));
                }
            }
            // 也可能是嵌套的业务对象，再递归一层
            JSONObject nested = tryNormalizeGeneric(parsedObj);
            if (nested != null) {
                return nested;
            }
        }
        if (payload instanceof JSONArray arr && !arr.isEmpty()) {
            String type = detectGenericTypeByItem(arr.getJSONObject(0));
            return buildResult(type, normalizeItems(arr, type));
        }
        return null;
    }

    private JSONObject tryNormalizeGeneric(JSONObject root) {
        for (String key : ARRAY_KEYS) {
            JSONArray arr = root.getJSONArray(key);
            if (arr != null && !arr.isEmpty()) {
                String type = arrayKeyToType(key);
                if (type == null) {
                    type = detectGenericTypeByItem(arr.getJSONObject(0));
                }
                return buildResult(type, normalizeItems(arr, type));
            }
        }
        // 根对象本身没有数组，再递归扫描一层子对象的数组
        for (String key : root.keySet()) {
            Object val = root.get(key);
            if (val instanceof JSONObject child) {
                for (String arrKey : ARRAY_KEYS) {
                    JSONArray arr = child.getJSONArray(arrKey);
                    if (arr != null && !arr.isEmpty()) {
                        String type = arrayKeyToType(arrKey);
                        if (type == null) {
                            type = detectGenericTypeByItem(arr.getJSONObject(0));
                        }
                        return buildResult(type, normalizeItems(arr, type));
                    }
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------
    // 类型推断
    // ------------------------------------------------------------

    private String detectFlyaiType(JSONObject firstItem) {
        if (firstItem == null) {
            return null;
        }
        // flyai 酒店可能把字段包在 info 里
        JSONObject info = firstItem.getJSONObject("info");
        JSONObject target = info != null ? info : firstItem;
        if (target.containsKey("mainPic") || target.containsKey("picUrl")
                || target.containsKey("score") || target.containsKey("star")) {
            return "hotel";
        }
        JSONArray journeys = firstItem.getJSONArray("journeys");
        if (journeys != null && !journeys.isEmpty()) {
            JSONObject journey = journeys.getJSONObject(0);
            JSONArray segments = journey != null ? journey.getJSONArray("segments") : null;
            if (segments != null && !segments.isEmpty()) {
                JSONObject seg = segments.getJSONObject(0);
                String transportType = seg.getString("transportType");
                if (isFlightTransportType(transportType)) {
                    return "flight";
                }
                if (isTrainTransportType(transportType)) {
                    return "train";
                }
                if (seg.containsKey("depStationCode") || seg.containsKey("depAirportCode")
                        || seg.containsKey("flightNo") || seg.containsKey("marketingTransportNo")) {
                    return "flight";
                }
                return "train";
            }
        }
        return null;
    }

    private boolean isFlightTransportType(String type) {
        if (type == null) {
            return false;
        }
        String t = type.toLowerCase();
        return Set.of("飞机", "flight", "flights", "air", "plane", "航班", "airplane").contains(t);
    }

    private boolean isTrainTransportType(String type) {
        if (type == null) {
            return false;
        }
        String t = type.toLowerCase();
        return Set.of("火车", "train", "trains", "rail", "高铁", "动车", "railway").contains(t);
    }

    private String detectGenericTypeByItem(JSONObject firstItem) {
        if (firstItem == null) {
            return "unknown";
        }
        if (firstItem.containsKey("journeys")) {
            return "flight";
        }
        // 航班常见平铺字段
        if (firstItem.containsKey("flightNo") || firstItem.containsKey("flightInfoId")
                || firstItem.containsKey("marketingFlightNo") || firstItem.containsKey("airlineCode")
                || firstItem.containsKey("departureAirportCode") || firstItem.containsKey("depAirportCode")
                || firstItem.containsKey("arrivalAirportCode") || firstItem.containsKey("arrAirportCode")) {
            return "flight";
        }
        // 火车常见平铺字段
        if (firstItem.containsKey("trainNo") || firstItem.containsKey("trainNum") || firstItem.containsKey("trainCode")
                || firstItem.containsKey("departureStationName") || firstItem.containsKey("arrivalStationName")) {
            // 用时间字段进一步确认，避免误识别
            if (firstItem.containsKey("depDateTime") || firstItem.containsKey("departureTime")
                    || firstItem.containsKey("departsDate") || firstItem.containsKey("departTime")) {
                return "train";
            }
        }
        if (firstItem.containsKey("mainPic") || firstItem.containsKey("picUrl")
                || firstItem.containsKey("imageUrl") || firstItem.containsKey("starRating")
                || firstItem.containsKey("address") || firstItem.containsKey("hotelName")) {
            return "hotel";
        }
        return "unknown";
    }

    private String arrayKeyToType(String key) {
        return switch (key) {
            case "flightList", "flights", "flightInfos" -> "flight";
            case "hotelList", "hotels", "hotelInformationList" -> "hotel";
            case "trainList", "trains" -> "train";
            default -> null;
        };
    }

    // ------------------------------------------------------------
    // 字段归一化
    // ------------------------------------------------------------

    private JSONArray normalizeItems(JSONArray src, String type) {
        JSONArray items = new JSONArray();
        for (int i = 0; i < src.size(); i++) {
            JSONObject obj = src.getJSONObject(i);
            if (obj == null) {
                continue;
            }
            // 跳过 MCP 内容块（{type:text,text:...}），避免把原始 JSON 文本当作业务条目展示
            if (isContentBlock(obj)) {
                continue;
            }
            JSONObject normalized = switch (type) {
                case "hotel" -> normalizeHotelItem(obj);
                case "flight", "train" -> normalizeTransportItem(obj, type);
                // unknown 类型原样返回，前端兜底展示
                default -> obj;
            };
            if (normalized != null) {
                items.add(normalized);
            }
        }
        return items;
    }

    private JSONObject normalizeHotelItem(JSONObject src) {
        JSONObject info = src.getJSONObject("info");
        JSONObject target = info != null ? info : src;

        JSONObject out = new JSONObject();
        out.put("name", firstNonBlank(
                target.getString("hotelName"),
                target.getString("title"),
                target.getString("name"),
                src.getString("name"),
                "未知酒店"
        ));
        out.put("address", firstNonBlank(
                target.getString("address"),
                src.getString("address"),
                ""
        ));
        out.put("mainPic", firstNonBlank(
                target.getString("firstPic"),
                target.getString("picUrl"),
                target.getString("mainPic"),
                target.getString("imageUrl"),
                src.getString("imageUrl")
        ));
        out.put("detailUrl", firstNonBlank(
                target.getString("jumpUrl"),
                target.getString("detailUrl"),
                target.getString("bookingUrl"),
                src.getString("bookingUrl"),
                src.getString("detailUrl")
        ));
        out.put("price", formatPrice(target.getString("lowestPrice"), target.getString("price"), src.getString("price")));
        out.put("brandName", firstNonBlank(target.getString("brandName"), target.getString("brand")));
        out.put("score", formatScore(target.getString("commentScore"), target.getString("score")));
        out.put("scoreDesc", target.getString("scoreDesc"));
        // 星级优先取途牛的 starName（如"豪华型"），否则回退到 star/starRating
        String star = firstNonBlank(target.getString("starName"));
        if (star.isBlank()) {
            star = normalizeStar(target.get("star"), target.get("starRating"));
        }
        out.put("star", star);
        out.put("interestsPoi", firstNonBlank(target.getString("business"), target.getString("interestsPoi")));
        out.put("review", firstNonBlank(target.getString("commentDigest"), target.getString("review")));
        return out;
    }

    private JSONArray mapRollingGoHotels(JSONArray src) {
        JSONArray items = new JSONArray();
        for (int i = 0; i < src.size(); i++) {
            JSONObject hotel = src.getJSONObject(i);
            if (hotel == null) {
                continue;
            }
            JSONObject out = new JSONObject();
            out.put("name", hotel.getString("name"));
            out.put("address", hotel.getString("address"));
            out.put("mainPic", hotel.getString("imageUrl"));
            out.put("detailUrl", hotel.getString("bookingUrl"));

            JSONObject priceObj = hotel.getJSONObject("price");
            if (priceObj != null && priceObj.containsKey("lowestPrice")) {
                out.put("price", "¥" + priceObj.getIntValue("lowestPrice"));
            } else {
                out.put("price", "");
            }

            Integer starRating = hotel.getInteger("starRating");
            if (starRating != null && starRating > 0) {
                out.put("star", "⭐".repeat(Math.min(starRating, 5)));
            }

            Integer distance = hotel.getInteger("distanceInMeters");
            if (distance != null && distance > 0) {
                String distDesc = distance >= 1000
                        ? String.format("距目标 %.1fkm", distance / 1000.0)
                        : "距目标 " + distance + "m";
                out.put("interestsPoi", distDesc);
            }
            items.add(out);
        }
        return items;
    }

    private JSONObject normalizeTransportItem(JSONObject src, String type) {
        // 若已经是 flyai 标准结构（含 journeys），直接透传
        if (src.containsKey("journeys")) {
            return src;
        }
        JSONObject out = new JSONObject();
        // 火车票（tuniu）price 为各席别价格对象，需取最低有效价；其余情形按平铺字段解析
        String derivedSeatClass = null;
        Object priceRaw = src.get("price");
        if (priceRaw instanceof JSONObject priceObj) {
            JSONObject lowest = extractLowestTrainPrice(priceObj);
            if (lowest != null) {
                out.put("adultPrice", lowest.getString("price"));
                derivedSeatClass = lowest.getString("seatClassName");
            } else {
                out.put("adultPrice", formatPrice(src.getString("adultPrice"), src.getString("salePrice")));
            }
        } else {
            out.put("adultPrice", formatPrice(src.getString("price"), src.getString("adultPrice"), src.getString("salePrice")));
        }
        out.put("jumpUrl", firstNonBlank(src.getString("jumpUrl"), src.getString("detailUrl"), src.getString("bookingUrl")));

        // 尝试把常见平铺字段构建成 journeys/segments 标准结构，让前端航班/火车卡片能直接渲染
        JSONObject segment = buildSegmentFromFlatFields(src, type);
        if (segment != null) {
            // 火车票最低价对应席别回填，前端卡片可展示座席类型
            if (derivedSeatClass != null && !derivedSeatClass.isBlank()
                    && (segment.getString("seatClassName") == null || segment.getString("seatClassName").isBlank())) {
                segment.put("seatClassName", derivedSeatClass);
            }
            JSONObject journey = new JSONObject();
            journey.put("journeyType", firstNonBlank(src.getString("journeyType"), src.getString("journey_type"), "直达"));
            journey.put("totalDuration", firstNonBlank(src.getString("duration"), src.getString("totalDuration"), src.getString("total_duration"), segment.getString("duration")));
            journey.put("segments", new JSONArray(List.of(segment)));
            out.put("journeys", new JSONArray(List.of(journey)));
        }

        // 保留原始字段，便于前端兜底以及展示额外信息
        for (String key : src.keySet()) {
            if (!out.containsKey(key)) {
                out.put(key, src.get(key));
            }
        }
        return out;
    }

    private JSONObject buildSegmentFromFlatFields(JSONObject src, String type) {
        String depDateTime = coalesce(src.getString("depDateTime"), src.getString("departureTime"), src.getString("departTime"), src.getString("departsDate"));
        String arrDateTime = coalesce(src.getString("arrDateTime"), src.getString("arrivalTime"), src.getString("arriveTime"), src.getString("arrivesDate"));

        String depStationName = coalesce(src.getString("depStationName"), src.getString("departureStationName"),
                src.getString("departStationName"), src.getString("dep_station_name"),
                src.getString("departureAirportName"), src.getString("depAirportName"));
        String arrStationName = coalesce(src.getString("arrStationName"), src.getString("arrivalStationName"),
                src.getString("destStationName"), src.getString("arr_station_name"),
                src.getString("arrivalAirportName"), src.getString("arrAirportName"));

        String depCityName = coalesce(src.getString("depCityName"), src.getString("departureCityName"), src.getString("dep_city_name"));
        String arrCityName = coalesce(src.getString("arrCityName"), src.getString("arrivalCityName"), src.getString("arr_city_name"));
        // 火车票（tuniu）通常只有车站名而无城市名，用车站名兜底，保证前端"始发→到达"展示完整
        if (depCityName == null) {
            depCityName = depStationName;
        }
        if (arrCityName == null) {
            arrCityName = arrStationName;
        }

        // 关键字段缺失时无法构建标准 segment，交给前端兜底展示
        if (depCityName == null || arrCityName == null || depDateTime == null || arrDateTime == null) {
            return null;
        }
        String depStationShortName = coalesce(src.getString("depStationShortName"), src.getString("departureAirportCode"),
                src.getString("depAirportCode"), src.getString("depStationCode"));
        String arrStationShortName = coalesce(src.getString("arrStationShortName"), src.getString("arrivalAirportCode"),
                src.getString("arrAirportCode"), src.getString("arrStationCode"));
        String duration = coalesce(src.getString("duration"), src.getString("elapsedTime"), src.getString("travelTime"));
        String transportNo = coalesce(src.getString("marketingTransportNo"), src.getString("flightNo"),
                src.getString("trainNo"), src.getString("trainNum"), src.getString("trainCode"), src.getString("flightNumber"));
        String transportName = coalesce(src.getString("marketingTransportName"), src.getString("airlineName"), src.getString("airline"));
        String seatClassName = coalesce(src.getString("seatClassName"), src.getString("cabinName"),
                src.getString("seatName"), src.getString("seatClass"), src.getString("cabinClass"));
        String depTerm = src.getString("depTerm");
        String arrTerm = src.getString("arrTerm");
        String depCityCode = coalesce(src.getString("depCityCode"), src.getString("departureCityCode"));
        String arrCityCode = coalesce(src.getString("arrCityCode"), src.getString("arrivalCityCode"));
        String transportType = coalesce(src.getString("transportType"), "flight".equals(type) ? "飞机" : "火车");

        JSONObject seg = new JSONObject();
        seg.put("depCityName", depCityName);
        seg.put("arrCityName", arrCityName);
        seg.put("depStationName", depStationName != null ? depStationName : "");
        seg.put("arrStationName", arrStationName != null ? arrStationName : "");
        seg.put("depStationShortName", depStationShortName != null ? depStationShortName : "");
        seg.put("arrStationShortName", arrStationShortName != null ? arrStationShortName : "");
        seg.put("depDateTime", depDateTime);
        seg.put("arrDateTime", arrDateTime);
        seg.put("duration", duration != null ? duration : "");
        seg.put("transportType", transportType != null ? transportType : "");
        seg.put("marketingTransportName", transportName != null ? transportName : "");
        seg.put("marketingTransportNo", transportNo != null ? transportNo : "");
        seg.put("seatClassName", seatClassName != null ? seatClassName : "");
        if (depTerm != null) {
            seg.put("depTerm", depTerm);
        }
        if (arrTerm != null) {
            seg.put("arrTerm", arrTerm);
        }
        if (depCityCode != null) {
            seg.put("depCityCode", depCityCode);
        }
        if (arrCityCode != null) {
            seg.put("arrCityCode", arrCityCode);
        }
        return seg;
    }

    /**
     * 从 tuniu 火车票的各席别价格对象中提取最低有效价及对应座席名。
     *
     * @return 含 price（形如 ¥576）与 seatClassName 的对象；无有效价时返回 null
     */
    private JSONObject extractLowestTrainPrice(JSONObject priceObj) {
        double min = Double.MAX_VALUE;
        String minKey = null;
        for (String key : TRAIN_SEAT_NAMES.keySet()) {
            String v = priceObj.getString(key);
            if (v == null || v.isBlank()) {
                continue;
            }
            try {
                double p = Double.parseDouble(v.trim());
                if (p > 0 && p < min) {
                    min = p;
                    minKey = key;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        if (minKey == null) {
            return null;
        }
        JSONObject out = new JSONObject();
        out.put("price", "¥" + formatAmount(min));
        out.put("seatClassName", TRAIN_SEAT_NAMES.get(minKey));
        return out;
    }

    /** 金额格式化：整数去掉小数（576.0 -> 576），否则保留原值。 */
    private String formatAmount(double amount) {
        if (amount == Math.floor(amount) && !Double.isInfinite(amount)) {
            return String.valueOf((long) amount);
        }
        return String.valueOf(amount);
    }

    // ------------------------------------------------------------
    // 工具方法
    // ------------------------------------------------------------

    private JSONObject buildResult(String type, JSONArray items) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        JSONObject result = new JSONObject();
        result.put("type", type);
        result.put("items", items);
        return result;
    }

    private String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return "";
    }

    private String coalesce(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private String formatPrice(String... candidates) {
        for (String p : candidates) {
            if (p == null || p.isBlank()) {
                continue;
            }
            String trimmed = p.trim();
            if (trimmed.startsWith("¥") || trimmed.startsWith("$")) {
                return trimmed;
            }
            try {
                Double.parseDouble(trimmed.replace(",", ""));
                return "¥" + trimmed;
            } catch (NumberFormatException ignored) {
                return trimmed;
            }
        }
        return "";
    }

    /**
     * 格式化评分：纯数字评分追加"分"后缀（如 4.7 -> 4.7分），已含文字则原样返回。
     */
    private String formatScore(String... candidates) {
        for (String s : candidates) {
            if (s == null || s.isBlank()) {
                continue;
            }
            String trimmed = s.trim();
            if (trimmed.matches("\\d+(\\.\\d+)?")) {
                return trimmed + "分";
            }
            return trimmed;
        }
        return "";
    }

    private String normalizeStar(Object starValue, Object starRatingValue) {
        if (starValue != null) {
            String s = starValue.toString();
            if (!s.isBlank()) {
                return s;
            }
        }
        if (starRatingValue instanceof Number n) {
            int rating = n.intValue();
            if (rating > 0) {
                return "⭐".repeat(Math.min(rating, 5));
            }
        }
        if (starRatingValue != null) {
            try {
                int rating = (int) Double.parseDouble(starRatingValue.toString());
                if (rating > 0) {
                    return "⭐".repeat(Math.min(rating, 5));
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }
}
