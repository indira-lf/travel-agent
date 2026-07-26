package com.travel.agent.tools;

import com.alibaba.fastjson2.JSON;
import com.travel.agent.context.AgentSessionContext;
import com.travel.business.policy.entity.PolicyCheckResult;
import com.travel.business.policy.entity.TravelPolicy;
import com.travel.business.policy.service.TravelPolicyService;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 差旅政策工具集：查询政策标准 + 合规校验，共享 TravelPolicyService。
 *
 * @author Hollis
 */
@Component
public class PolicyTools {

    private static final Logger logger = LoggerFactory.getLogger(PolicyTools.class);

    @Autowired
    private TravelPolicyService travelPolicyService;

    @Tool(name = "query_travel_policy",
            description = "查询指定用户和目的城市的差旅政策标准（餐标、酒店标准、交通补贴等）")
    public String queryTravelPolicy(
            AgentSessionContext sessionCtx,
            @ToolParam(name = "city", description = "目的城市") String city) {

        String travelPolicy = sessionCtx.getTravelPolicy(city);
        if (travelPolicy != null) {
            logger.info("[TOOL][query_travel_policy] hit cache, city={}", city);
            return travelPolicy;
        } else {
            logger.info("[TOOL][query_travel_policy] miss cache");
            String userId = sessionCtx.getUserId();
            logger.info("[TOOL][query_travel_policy] userId={}, city={}", userId, city);
            TravelPolicy policy = travelPolicyService.getPolicy(userId, city);
            String result = JSON.toJSONString(policy);
            logger.info("[TOOL][query_travel_policy] result={}", result);
            sessionCtx.setTravelPolicy(city, result);
            return result;
        }

    }

    //fixme 检查和 查询 分开

    @Tool(name = "check_travel_policy",
            description = "校验行程或订单是否符合差旅政策，返回合规结果和超标说明")
    public String checkTravelPolicy(
            AgentSessionContext sessionCtx,
            @ToolParam(name = "city", description = "目的城市") String city,
            @ToolParam(name = "order_summary",
                    description = "订单摘要JSON，例如 {\"type\":\"HOTEL\",\"amount\":450}") String orderSummary) {

        String userId = sessionCtx.getUserId();
        logger.info("[TOOL][check_travel_policy] userId={}, city={}, orderSummary={}", userId, city, orderSummary);
        TravelPolicy policy = travelPolicyService.getPolicy(userId, city);
        PolicyCheckResult result = travelPolicyService.checkCompliance(orderSummary, policy);
        String json = JSON.toJSONString(result);
        logger.info("[TOOL][check_travel_policy] done");
        return json;
    }
}
