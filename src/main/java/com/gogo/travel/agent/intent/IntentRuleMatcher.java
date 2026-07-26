package com.gogo.travel.agent.intent;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * L1 规则/关键词匹配器。
 *
 * <p>针对意图清晰、表达高度模板化的高频场景（寒暄、报销、政策查询、查询类等），
 * 在内存中做关键词/正则匹配，命中后立即返回 {@link IntentRecognitionResult}，
 * 目标延迟 &lt; 50ms。规则集按优先级（List 顺序）求值，命中即短路。</p>
 *
 * <p>注意：L1 应当保守，宁可漏判也不要误判；不确定时直接放行让 L2/L3 处理。</p>
 *
 * @author Hollis
 */
@Component
public class IntentRuleMatcher {

    /**
     * 单条规则：keyword 任一命中且不含 negativeKeyword 即视为命中。
     */
    private static final class Rule {
        final IntentCategory category;
        final Pattern keyword;
        final List<Pattern> negativeKeywords;

        Rule(IntentCategory category, String keyword, List<String> negativeKeywords) {
            this.category = category;
            this.keyword = compile(keyword);
            this.negativeKeywords = negativeKeywords == null
                    ? List.of()
                    : negativeKeywords.stream().map(IntentRuleMatcher::compile).toList();
        }

        boolean matches(String text) {
            if (!keyword.matcher(text).find()) {
                return false;
            }
            for (Pattern negative : negativeKeywords) {
                if (negative.matcher(text).find()) {
                    return false;
                }
            }
            return true;
        }
    }

    private final List<Rule> rules = new ArrayList<>();

    public IntentRuleMatcher() {
        // 优先级从高到低（List 顺序即优先级，命中即短路）。
        // 说明：L1 命中会直接短路并跳过“问题改写”，因此规则需要在“高召回”与“高精度”之间平衡——
        // 关键词尽量覆盖企业差旅真实口语化表达以提升命中率，同时用 negativeKeywords 排除跨类干扰，
        // 并借助规则顺序把“具体动作类（报销/政策/审批/取消/修改）”排在“泛化查询/预订类”之前，避免误路由。

        // 寒暄：整句只由问候/礼貌用语（可多段叠加、可含标点）构成才命中，
        // 既支持“下午好，请问在吗？”这类多段问候，又能避免“你好，帮我订机票”被误判为寒暄。
        String greet = "你好|您好|哈喽|哈啰|嗨|hi|hello|hey|早上好|早安|上午好|中午好|下午好|晚上好"
                + "|在吗|在不在|在么|在不|有人吗|有人在吗|你在吗|请问|请教一下|打扰一下|打扰了|方便吗";
        String sep = "[\\s,，。.!！?？～~、]*";
        addRule(IntentCategory.GREETING,
                "^(?:" + greet + ")(?:" + sep + "(?:" + greet + "))*" + sep + "$");

        // 报销：动作性极强，优先级高；排除“报销政策/标准/额度/能不能报”等政策类问法，交给政策查询。
        addRule(IntentCategory.REIMBURSEMENT,
                "(报销|报账|报帐|贴票|发票|报销单|费用报销|差旅报销|出差费用|生成报销单|识别发票|发票识别|提交报销|报一下|帮我报|报个销|走报销|电子发票|机票行程单)",
                "政策", "标准", "规定", "制度", "额度", "限额", "能不能报", "能报吗", "报销吗", "可以报", "怎么报", "报销范围", "报销比例");

        // 政策/标准：覆盖企业常用术语（差标、超标、餐标、舱位标准等）。
        addRule(IntentCategory.POLICY_QUERY,
                "(差旅政策|差旅规定|差旅制度|差旅标准|差标|超标|餐标|餐费标准|住宿标准|酒店标准|机票标准|舱位标准|高铁标准|座位标准|费用标准|报销标准|报销政策|报销规定|报销额度|报销范围|能不能报|可以报销吗|能报销吗|预订规定|预定规定|订票规定|购票规定|签证|入境政策|出差政策|出行政策|差旅管理|出差规定|出差标准)");

        // 审批查询：仅查状态/进度/结果，关键词已足够特异，无需额外排除项。
        addRule(IntentCategory.APPROVAL_QUERY,
                "(审批进度|审批状态|审批结果|审批通过了?吗?|审批到哪|审批到哪个|审批环节|审批意见|审批人|审批流程|我的审批|审批单状态|批了吗|批没批|审没审|通过了没|领导.*批|谁.*审批)");

        // 取消出差/审批。
        addRule(IntentCategory.TRAVEL_CANCEL,
                "(取消出差|取消差旅|取消审批|取消我的(差旅|出差)|撤回(差旅|出差|审批)?申请|撤销(差旅|出差|审批)?申请|撤回审批|这次不去了?|不出差了|出差取消了?|把.*(差旅|出差|申请).*撤了?)");

        // 修改差旅申请；排除“取消/撤回”避免与取消类重叠。
        addRule(IntentCategory.TRAVEL_MODIFY,
                "((修改|变更).*(差旅|出差|申请|行程|订单)|改期|延期|(差旅|出差).*改一?下?|改一下.*(日期|时间|目的地|行程)|调整.*(日期|时间|行程)|把.*(日期|时间|目的地).*改)",
                "取消", "撤回", "撤销");

        // 已有差旅单查询；排除“提交/发起/新建”等创建动作、“做一份/方案”等规划动作与“取消/修改/规划/报销”跨类词。
        addRule(IntentCategory.TRAVEL_ORDER_QUERY,
                "(差旅行程|差旅单|出差单|差旅订单|差旅详情|差旅记录|出差记录|我的差旅|我的出差|出差安排|差旅安排|差旅单详情|出差单状态|差旅单状态|上次的?(差旅|出差)|历史(差旅|出差))",
                "提交", "发起", "提个", "新建", "报备", "取消", "规划", "报销", "做一份", "做个", "做一下", "出一份", "方案");

        // 景点/旅游信息。
        addRule(IntentCategory.ATTRACTIONS_QUERY,
                "(有什么好玩|好玩的地方|景点|景区|风景区|名胜|游玩|游览|打卡|必去|必玩|一日游|周边游|当地特色|有什么好吃|美食推荐|特产)");

        // 天气/交通/新闻等通用信息；排除机酒火与预订/报销，避免抢占更具体的意图。
        addRule(IntentCategory.GENERAL_INFO,
                "(天气|气温|多少度|冷不冷|热不热|下雨|下雪|限行|路况|堵不堵|怎么去|怎么走|地铁|公交|打车|时差|汇率|新闻|资讯)",
                "机票", "航班", "火车票", "高铁票", "订", "预订", "报销");

        // 行程规划（必须排在 FLIGHT/TRAIN/HOTEL_SEARCH 之前，优先捕获同时包含“规划+行程/方案”的表达，
        addRule(IntentCategory.ITINERARY_PLANNING,
                "(规划.*行程|安排.*行程|做.*行程|行程规划|行程安排|行程方案|出行方案|做一份行程|出一份行程|做个行程|帮我规划|规划一下|帮我安排一下)");

        // 机票查询；排除发票/报销/标准/政策与取消/退票/改签（后者交给预订类处理）。
        addRule(IntentCategory.FLIGHT_SEARCH,
                "(查机票|订机票|搜机票|看机票|买机票|机票|航班|飞机票|航班信息|航班时刻|头等舱|经济舱|公务舱|往返机票|单程机票|直飞|廉价航班)",
                "发票", "报销", "标准", "政策", "取消", "退票", "改签");

        // 火车/高铁查询。
        addRule(IntentCategory.TRAIN_SEARCH,
                "(查火车|订火车|搜火车|看火车|买火车票|高铁|动车|火车票|火车|车次|列车|高铁票|城际|二等座|一等座|商务座)",
                "标准", "政策", "取消", "退票", "改签");

        // 酒店查询。
        addRule(IntentCategory.HOTEL_SEARCH,
                "(查酒店|订酒店|搜酒店|看酒店|住酒店|附近.*酒店|酒店|住宿|住哪里?|住哪儿|入住|宾馆|民宿|快捷酒店|连锁酒店|标间|大床房)",
                "标准", "政策", "报销", "取消", "退订");

        // 预订/改签/退票（通用兜底，放在具体机/酒/火之后，避免抢占具体查询意图）。
        addRule(IntentCategory.BOOKING,
                "(预订|下单|订这个|订下来|就订(这个|它)|帮我订(这个|下)|确认(预订|下单|预定)|改签|退票|退订|取消(预订|订单|机票|酒店|火车票))");

        // 新建差旅申请（放在最后，避免抢占查询/规划/修改类）；排除审批状态查询与取消/查询/规划等。
        addRule(IntentCategory.TRAVEL_APPLICATION,
                "(申请出差|出差申请|申请.{0,10}出差|发起(差旅|出差)|提个.*(出差|申请)|提交.*(出差|差旅|申请)|帮我提.*(出差|申请)|我要出差|我想出差|我需要出差|我要去.*出差|(下周|下个月|明天|后天|下下周).*出差|新建(差旅|出差)|报备出差|出差报备)",
                "审批进度", "审批状态", "审批结果", "取消", "查", "规划", "报销");
    }

    private void addRule(IntentCategory category, String keyword) {
        rules.add(new Rule(category, keyword, null));
    }

    /**
     * 注册带排除词的规则：keyword 命中且不含任一 negativeKeyword 时才判定命中。
     * negativeKeyword 同样按正则编译，便于排除跨类干扰、提升 L1 精度。
     */
    private void addRule(IntentCategory category, String keyword, String... negativeKeywords) {
        rules.add(new Rule(category, keyword, List.of(negativeKeywords)));
    }

    private static Pattern compile(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    /**
     * 对输入文本执行 L1 规则匹配。
     *
     * @param text 改写后的问题文本（已 trim）
     * @return 命中时返回结果，未命中返回 {@link Optional#empty()}
     */
    public Optional<IntentRecognitionResult> match(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String normalized = text.trim();

        for (Rule rule : rules) {
            if (rule.matches(normalized)) {
                return Optional.of(IntentRecognitionResult.single(
                        IntentRecognitionResult.Source.RULE,
                        rule.category,
                        IntentRecognitionResult.Confidence.HIGH,
                        "L1 规则命中：关键词匹配到「" + rule.category.getDescription() + "」",
                        null));
            }
        }
        return Optional.empty();
    }

    /**
     * 仅用于单元测试 / 调试：返回当前注册的规则类别。
     */
    Map<IntentCategory, Integer> ruleStats() {
        Map<IntentCategory, Integer> stats = new LinkedHashMap<>();
        for (Rule rule : rules) {
            stats.merge(rule.category, 1, Integer::sum);
        }
        return stats;
    }
}
