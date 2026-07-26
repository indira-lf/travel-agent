package com.travel.agent.tools;

import com.alibaba.fastjson2.JSON;
import com.travel.agent.context.AgentSessionContext;
import com.travel.business.user.entity.UserProfile;
import com.travel.business.user.repo.UserProfileRepository;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 用户联系信息工具集：查询 + 更新姓名拼音、邮箱、中文姓名、证件类型、证件号码、手机号、性别，
 * 用于酒店/机票预订时自动填充乘客与联系人字段。
 *
 * @author Hollis
 */
@Component
public class QueryUserInfoTools {

    private static final Logger logger = LoggerFactory.getLogger(QueryUserInfoTools.class);

    /** 证件类型代码 → 中文名（与 flight-manager 数据字典一致） */
    private static final Map<Integer, String> ID_TYPE_LABEL = Map.ofEntries(
            Map.entry(0, "身份证"),
            Map.entry(1, "护照"),
            Map.entry(2, "其他"),
            Map.entry(3, "回乡证"),
            Map.entry(4, "军官证"),
            Map.entry(5, "警官证"),
            Map.entry(6, "港澳通行证"),
            Map.entry(7, "台胞证"),
            Map.entry(8, "台湾通行证"),
            Map.entry(9, "外国人永久居留身份证")
    );

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Tool(name = "query_user_contact_info",
          description = "查询用户的联系信息和乘机人信息，包括姓名拼音、邮箱、中文姓名、证件类型、证件号码、手机号、性别。"
                      + "用于酒店/机票预订时自动填充联系人和乘客信息。"
                      + "若档案中缺少相关信息，返回提示让 Agent 向用户追问。")
    public String queryUserContactInfo(AgentSessionContext sessionCtx) {
        String cached = sessionCtx.getUserContactInfo();
        if (cached != null) {
            logger.info("[TOOL][query_user_contact_info] hit cache, userId={}", sessionCtx.getUserId());
            return cached;
        }

        String userId = sessionCtx.getUserId();
        logger.info("[TOOL][query_user_contact_info] miss cache, userId={}", userId);

        Optional<UserProfile> optProfile = userProfileRepository.findByUserId(userId);
        Map<String, Object> result = new HashMap<>();
        result.put("userId", userId);

        if (optProfile.isPresent()) {
            UserProfile profile = optProfile.get();
            String namePinyin = profile.getNamePinyin();
            String email = profile.getEmail();
            String chineseName = profile.getChineseName();
            Integer idType = profile.getIdType();
            String idNumber = profile.getIdNumber();
            String phone = profile.getPhone();
            String gender = profile.getGender();

            boolean hasNamePinyin = namePinyin != null && !namePinyin.isBlank();
            boolean hasEmail = email != null && !email.isBlank();
            boolean hasChineseName = chineseName != null && !chineseName.isBlank();
            boolean hasIdType = idType != null;
            boolean hasIdNumber = idNumber != null && !idNumber.isBlank();
            boolean hasPhone = phone != null && !phone.isBlank();
            boolean hasGender = gender != null && !gender.isBlank();

            // 酒店联系人信息（姓名拼音 + 邮箱）
            if (hasNamePinyin) {
                String[] parts = namePinyin.trim().split("\\s+", 2);
                result.put("lastName", parts[0]);
                result.put("firstName", parts.length > 1 ? parts[1] : "");
                result.put("namePinyin", namePinyin);
            }
            if (hasEmail) {
                result.put("email", email);
            }

            // 机票乘客信息（中文姓名 + 证件 + 手机 + 性别）
            if (hasChineseName) {
                result.put("chineseName", chineseName);
            }
            if (hasIdType) {
                result.put("idType", idType);
                result.put("idTypeLabel", ID_TYPE_LABEL.getOrDefault(idType, "未知"));
            }
            if (hasIdNumber) {
                result.put("idNumber", idNumber);
            }
            if (hasPhone) {
                result.put("phone", phone);
            }
            if (hasGender) {
                result.put("gender", gender);
            }

            // 酒店联系人完整性
            boolean hotelComplete = hasNamePinyin && hasEmail;
            result.put("hotelComplete", hotelComplete);

            // 机票乘客信息完整性
            boolean flightComplete = hasChineseName && hasIdType && hasIdNumber && hasPhone && hasGender;
            result.put("flightComplete", flightComplete);

            // 综合完整性
            result.put("complete", hotelComplete && flightComplete);

            if (!hotelComplete || !flightComplete) {
                StringBuilder hint = new StringBuilder("用户档案中缺少以下信息，请向用户追问：");
                if (!hasNamePinyin) {
                    hint.append("姓名拼音（如 ZHANG SAN）；");
                }
                if (!hasEmail) {
                    hint.append("邮箱地址；");
                }
                if (!hasChineseName) {
                    hint.append("中文姓名（如 张三）；");
                }
                if (!hasIdType) {
                    hint.append("证件类型（身份证/护照/军官证等，0-身份证 1-护照 2-其他 3-回乡证 4-军官证 5-警官证 6-港澳通行证 7-台胞证 8-台湾通行证 9-外国人永久居留身份证）；");
                }
                if (!hasIdNumber) {
                    hint.append("证件号码（身份证/护照号）；");
                }
                if (!hasPhone) {
                    hint.append("手机号；");
                }
                if (!hasGender) {
                    hint.append("性别（男/女）；");
                }
                result.put("message", hint.toString());
            }
        } else {
            result.put("complete", false);
            result.put("hotelComplete", false);
            result.put("flightComplete", false);
            result.put("message", "未找到用户档案，请向用户询问姓名拼音、邮箱、中文姓名、证件类型、证件号码、手机号、性别");
        }

        String json = JSON.toJSONString(result);
        sessionCtx.setUserContactInfo(json);
        logger.info("[TOOL][query_user_contact_info] result={}", json);
        return json;
    }

    /**
     * 更新用户的联系信息和乘机人信息。用户主动提供新信息时调用，自动保存到档案。
     */
    @Tool(name = "update_user_contact_info",
          description = "更新用户的联系信息和乘机人信息。支持以下字段："
                      + "name_pinyin（姓名拼音，格式：大写姓在前名在后空格分隔，如 ZHANG SAN）、"
                      + "email（邮箱）、"
                      + "chinese_name（中文姓名，如 张三）、"
                      + "id_type（证件类型：0-身份证 1-护照 2-其他 3-回乡证 4-军官证 5-警官证 6-港澳通行证 7-台胞证 8-台湾通行证 9-外国人永久居留身份证）、"
                      + "id_number（证件号码，身份证/护照号）、"
                      + "phone（手机号）、"
                      + "gender（性别，M-男 F-女）。"
                      + "当用户主动提供或修正了以上任意信息时调用，至少提供一个字段。")
    public String updateUserContactInfo(
            AgentSessionContext sessionCtx,
            @ToolParam(name = "name_pinyin",
                       description = "姓名拼音（大写，姓在前名在后，空格分隔），如 ZHANG SAN。不更新时传 null。",
                       required = false) String namePinyin,
            @ToolParam(name = "email",
                       description = "用户邮箱地址。不更新时传 null。",
                       required = false) String email,
            @ToolParam(name = "chinese_name",
                       description = "中文姓名，如 张三。不更新时传 null。",
                       required = false) String chineseName,
            @ToolParam(name = "id_type",
                       description = "证件类型：0-身份证 1-护照 2-其他 3-回乡证 4-军官证 5-警官证 6-港澳通行证 7-台胞证 8-台湾通行证 9-外国人永久居留身份证。不更新时传 null。",
                       required = false) Integer idType,
            @ToolParam(name = "id_number",
                       description = "证件号码（身份证/护照号）。不更新时传 null。",
                       required = false) String idNumber,
            @ToolParam(name = "phone",
                       description = "手机号。不更新时传 null。",
                       required = false) String phone,
            @ToolParam(name = "gender",
                       description = "性别：M-男，F-女。不更新时传 null。",
                       required = false) String gender) {

        String userId = sessionCtx.getUserId();
        logger.info("[TOOL][update_user_contact_info] userId={}, namePinyin={}, email={}, chineseName={}, idType={}, idNumber={}, phone={}, gender={}",
                userId, namePinyin, email, chineseName, idType, idNumber, phone, gender);

        // 至少需要一个字段
        boolean hasNamePinyin = namePinyin != null && !namePinyin.isBlank();
        boolean hasEmail = email != null && !email.isBlank();
        boolean hasChineseName = chineseName != null && !chineseName.isBlank();
        boolean hasIdType = idType != null;
        boolean hasIdNumber = idNumber != null && !idNumber.isBlank();
        boolean hasPhone = phone != null && !phone.isBlank();
        boolean hasGender = gender != null && !gender.isBlank();

        if (!hasNamePinyin && !hasEmail && !hasChineseName && !hasIdType && !hasIdNumber && !hasPhone && !hasGender) {
            return JSON.toJSONString(Map.of("success", false, "message",
                    "至少需要提供其中一个字段：name_pinyin / email / chinese_name / id_type / id_number / phone / gender"));
        }

        Optional<UserProfile> optProfile = userProfileRepository.findByUserId(userId);
        if (optProfile.isEmpty()) {
            return JSON.toJSONString(Map.of("success", false, "message", "未找到用户档案，无法更新"));
        }

        UserProfile profile = optProfile.get();
        List<String> updatedFields = new ArrayList<>();

        if (hasNamePinyin) {
            profile.setNamePinyin(namePinyin.trim().toUpperCase());
            updatedFields.add("name_pinyin");
        }
        if (hasEmail) {
            profile.setEmail(email.trim());
            updatedFields.add("email");
        }
        if (hasChineseName) {
            profile.setChineseName(chineseName.trim());
            updatedFields.add("chinese_name");
        }
        if (hasIdType) {
            profile.setIdType(idType);
            updatedFields.add("id_type");
        }
        if (hasIdNumber) {
            profile.setIdNumber(idNumber.trim());
            updatedFields.add("id_number");
        }
        if (hasPhone) {
            profile.setPhone(phone.trim());
            updatedFields.add("phone");
        }
        if (hasGender) {
            // 支持中文输入，统一转为 M/F
            String normalizedGender = normalizeGender(gender.trim());
            profile.setGender(normalizedGender);
            updatedFields.add("gender");
        }

        userProfileRepository.save(profile);
        // 档案已变更，失效会话内的联系信息缓存，下次查询回源数据库
        sessionCtx.invalidateUserContactInfo();

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("userId", userId);
        result.put("updatedFields", updatedFields);
        if (hasNamePinyin) {
            result.put("namePinyin", profile.getNamePinyin());
        }
        if (hasEmail) {
            result.put("email", profile.getEmail());
        }
        if (hasChineseName) {
            result.put("chineseName", profile.getChineseName());
        }
        if (hasIdType) {
            result.put("idType", profile.getIdType());
            result.put("idTypeLabel", ID_TYPE_LABEL.getOrDefault(profile.getIdType(), "未知"));
        }
        if (hasIdNumber) {
            result.put("idNumber", profile.getIdNumber());
        }
        if (hasPhone) {
            result.put("phone", profile.getPhone());
        }
        if (hasGender) {
            result.put("gender", profile.getGender());
        }

        String json = JSON.toJSONString(result);
        logger.info("[TOOL][update_user_contact_info] result={}", json);
        return json;
    }

    /**
     * 将性别输入统一为 M/F。
     */
    private String normalizeGender(String input) {
        if (input == null) {
            return null;
        }
        String upper = input.toUpperCase();
        if ("M".equals(upper) || "男".equals(input) || "MALE".equals(upper)) {
            return "M";
        }
        if ("F".equals(upper) || "女".equals(input) || "FEMALE".equals(upper)) {
            return "F";
        }
        // 原样返回，由调用方保证有效性
        return upper;
    }

    /**
     * 查询用户常驻/办公城市，用于在用户未提供出发地时推断出发城市。
     */
    @Tool(name = "query_user_base_location", description = "查询用户常驻/办公城市，用于在用户未提供出发地时推断出发城市")
    public String queryUserBaseLocation(AgentSessionContext sessionCtx) {
        String cached = sessionCtx.getUserBaseLocation();
        if (cached != null) {
            logger.info("[TOOL][query_user_base_location] hit cache, userId={}", sessionCtx.getUserId());
            return cached;
        }

        String userId = sessionCtx.getUserId();
        logger.info("[TOOL][query_user_base_location] miss cache, userId={}", userId);

        Optional<UserProfile> profile = userProfileRepository.findByUserId(userId);
        String result;
        if (profile.isPresent()) {
            String baseCity = profile.get().getBaseCity();
            if (baseCity != null && !baseCity.isBlank()) {
                result = JSON.toJSONString(Map.of("userId", userId, "baseCity", baseCity));
            } else {
                // 用户存在但未配置常驻城市，提示 Agent 向用户追问出发地
                result = JSON.toJSONString(Map.of("userId", userId, "baseCity", "",
                        "message", "未查询到用户的常驻城市，请向用户询问出发城市"));
            }
        } else {
            // 用户档案不存在，同样提示追问
            result = JSON.toJSONString(Map.of("userId", userId, "baseCity", "",
                    "message", "未找到用户档案，请向用户询问出发城市"));
        }
        sessionCtx.setUserBaseLocation(result);
        logger.info("[TOOL][query_user_base_location] result={}", result);
        return result;
    }
}
