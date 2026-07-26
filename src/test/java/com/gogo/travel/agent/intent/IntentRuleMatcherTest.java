package com.gogo.travel.agent.intent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link IntentRuleMatcher} L1 规则命中率与精度测试。
 *
 * <p>用例全部取自企业差旅真实口语化表达，并对齐
 * {@code IntentRouterKnowledgeConfig} 的 L2 种子语料。测试分三类：</p>
 * <ul>
 *   <li>expectMatch：清晰模板化表达，L1 应命中且分到正确意图类别；</li>
 *   <li>expectAgent：同一目标智能体内部存在歧义（如 flight/booking 都归 ItineraryPlanAgent），
 *       只校验落到正确的目标子智能体（分组）即可，因为分组内误判不影响路由；</li>
 *   <li>expectEmpty：信息不足/开放式问法，L1 应保守放行（返回 empty），交给 L2/L3。</li>
 * </ul>
 *
 * <p>测试收集全部失败用例后一次性断言，便于查看整体命中情况。</p>
 *
 * @author Hollis
 */
@DisplayName("IntentRuleMatcher L1 规则命中率测试")
class IntentRuleMatcherTest {

    private final IntentRuleMatcher matcher = new IntentRuleMatcher();

    @Test
    @DisplayName("清晰表达应命中正确意图类别")
    void shouldHitExpectedCategory() {
        List<Case> cases = new ArrayList<>();

        // —— 寒暄 GREETING ——
        expect(cases, IntentCategory.GREETING, "你好", "您好", "在吗？", "hi", "hello",
                "早上好", "下午好，请问在吗？", "嗨~");

        // —— 报销 REIMBURSEMENT ——
        expect(cases, IntentCategory.REIMBURSEMENT,
                "帮我报销这张机票", "报销", "识别一下发票", "生成报销单",
                "帮我报一下这次出差的费用", "提交报销", "差旅报销", "帮我报个销");

        // —— 政策/标准 POLICY_QUERY（含与报销的跨类边界）——
        expect(cases, IntentCategory.POLICY_QUERY,
                "差旅政策是什么", "餐标是多少", "出差住宿标准", "机票预订规定",
                "签证怎么办", "入境政策", "差旅管理规定",
                "报销政策", "报销标准是多少", "差标是多少", "酒店住宿标准是多少");

        // —— 审批查询 APPROVAL_QUERY ——
        expect(cases, IntentCategory.APPROVAL_QUERY,
                "我的审批到哪了", "查一下审批进度", "我的出差申请审批通过了吗",
                "审批状态", "审批结果怎么样", "领导审批了吗", "审批到哪个环节了");

        // —— 取消 TRAVEL_CANCEL ——
        expect(cases, IntentCategory.TRAVEL_CANCEL,
                "取消出差", "撤回出差申请", "取消审批", "取消我的差旅申请",
                "这次不去了", "不出差了");

        // —— 修改 TRAVEL_MODIFY ——
        expect(cases, IntentCategory.TRAVEL_MODIFY,
                "修改出差申请", "改一下出差日期", "修改我的差旅申请",
                "出差目的地改了", "把出差日期改一下");

        // —— 已有差旅单查询 TRAVEL_ORDER_QUERY ——
        expect(cases, IntentCategory.TRAVEL_ORDER_QUERY,
                "查一下我的差旅行程", "我的出差单状态", "查看我的出差安排",
                "查一下这个差旅订单", "我本周有出差安排吗？", "差旅单详情", "我上次的差旅订单");

        // —— 新建差旅申请 TRAVEL_APPLICATION ——
        expect(cases, IntentCategory.TRAVEL_APPLICATION,
                "我要出差", "帮我提交一个出差申请", "提个出差单", "发起差旅申请",
                "我下周要去上海拜访客户，帮我提个出差申请", "报备出差", "申请去北京出差");

        // —— 行程规划 ITINERARY_PLANNING ——
        expect(cases, IntentCategory.ITINERARY_PLANNING,
                "帮我规划行程", "安排一下杭州的行程", "我下周去北京出差，帮我做一下行程规划",
                "出差行程方案", "帮我做一份差旅行程", "规划一下这几天的安排");

        // —— 机票查询 FLIGHT_SEARCH ——
        expect(cases, IntentCategory.FLIGHT_SEARCH,
                "查机票", "北京到杭州的航班", "帮我查一下航班",
                "看看有没有去上海的机票", "订机票", "航班时刻表");

        // —— 火车查询 TRAIN_SEARCH ——
        expect(cases, IntentCategory.TRAIN_SEARCH,
                "查火车票", "北京到上海的高铁", "动车票", "帮我查一下火车",
                "看一下车次", "高铁票查询");

        // —— 酒店查询 HOTEL_SEARCH ——
        expect(cases, IntentCategory.HOTEL_SEARCH,
                "查酒店", "杭州的酒店", "附近酒店", "帮我订个酒店",
                "出差住宿推荐", "我住哪里比较好");

        // —— 通用信息 GENERAL_INFO ——
        expect(cases, IntentCategory.GENERAL_INFO,
                "今天北京天气怎么样", "怎么去机场", "上海地铁线路",
                "杭州有什么新闻", "交通路况怎么样");

        // —— 景点 ATTRACTIONS_QUERY ——
        expect(cases, IntentCategory.ATTRACTIONS_QUERY,
                "杭州有什么好玩的", "景点推荐", "上海旅游景点",
                "北京有哪些必去的景点", "当地有什么好玩的地方");

        // —— 跨类边界陷阱（分错组会导致路由到错误子智能体）——
        expect(cases, IntentCategory.FLIGHT_SEARCH, "你好，帮我订机票");
        expect(cases, IntentCategory.BOOKING, "取消酒店预订", "改签机票", "退票");

        runCategoryCases(cases);
    }

    @Test
    @DisplayName("分组内歧义只需命中正确目标子智能体")
    void shouldHitExpectedTargetAgent() {
        List<AgentCase> cases = new ArrayList<>();

        // 预订类与查询类同属 ItineraryPlanAgent，分组内误判不影响路由
        expectAgent(cases, "ItineraryPlanAgent",
                "预订这个", "帮我把这个订下来", "确认下单", "帮我预订这个航班");

        // 各类差旅单操作同属 ItineraryManageAgent
        expectAgent(cases, "ItineraryManageAgent", "帮我看看这个出差申请");

        runAgentCases(cases);
    }

    @Test
    @DisplayName("信息不足的开放式问法 L1 应保守放行")
    void shouldDeferAmbiguousToLowerLayers() {
        List<String> shouldBeEmpty = List.of(
                "随便问问", "你能做什么", "有什么功能", "帮助", "这个怎么说");

        List<String> failures = new ArrayList<>();
        for (String text : shouldBeEmpty) {
            Optional<IntentRecognitionResult> result = matcher.match(text);
            if (result.isPresent()) {
                failures.add(String.format("「%s」期望 L1 放行(empty)，实际命中=%s",
                        text, result.get().getPrimary()));
            }
        }
        assertTrue(failures.isEmpty(), buildReport("过度命中(应放行却命中)", failures));
    }

    // ----------------------------------------------------------------------
    // helpers
    // ----------------------------------------------------------------------

    private void runCategoryCases(List<Case> cases) {
        List<String> failures = new ArrayList<>();
        for (Case c : cases) {
            Optional<IntentRecognitionResult> result = matcher.match(c.text);
            if (result.isEmpty()) {
                failures.add(String.format("「%s」期望=%s，实际=未命中(L1 miss)", c.text, c.expected));
            } else if (result.get().getPrimary() != c.expected) {
                failures.add(String.format("「%s」期望=%s，实际=%s",
                        c.text, c.expected, result.get().getPrimary()));
            }
        }
        assertTrue(failures.isEmpty(), buildReport("意图类别不匹配", failures));
    }

    private void runAgentCases(List<AgentCase> cases) {
        List<String> failures = new ArrayList<>();
        for (AgentCase c : cases) {
            Optional<IntentRecognitionResult> result = matcher.match(c.text);
            if (result.isEmpty()) {
                failures.add(String.format("「%s」期望目标=%s，实际=未命中(L1 miss)", c.text, c.expectedAgent));
            } else {
                String actual = result.get().getPrimary().getDefaultTargetAgent();
                if (!c.expectedAgent.equals(actual)) {
                    failures.add(String.format("「%s」期望目标=%s，实际=%s(%s)",
                            c.text, c.expectedAgent, actual, result.get().getPrimary()));
                }
            }
        }
        assertTrue(failures.isEmpty(), buildReport("目标子智能体不匹配", failures));
    }

    private static void expect(List<Case> cases, IntentCategory expected, String... texts) {
        for (String text : texts) {
            cases.add(new Case(text, expected));
        }
    }

    private static void expectAgent(List<AgentCase> cases, String expectedAgent, String... texts) {
        for (String text : texts) {
            cases.add(new AgentCase(text, expectedAgent));
        }
    }

    private static String buildReport(String title, List<String> failures) {
        StringBuilder sb = new StringBuilder();
        sb.append(title).append("，共 ").append(failures.size()).append(" 条失败：\n");
        for (String f : failures) {
            sb.append("  - ").append(f).append('\n');
        }
        return sb.toString();
    }

    private record Case(String text, IntentCategory expected) {
    }

    private record AgentCase(String text, String expectedAgent) {
    }
}
