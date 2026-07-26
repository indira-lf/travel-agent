package com.travel.agent.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * @author Hollis
 */
@Component
public class InfoQueryTools {

    private static final Logger logger = LoggerFactory.getLogger(InfoQueryTools.class);

    @Tool(name = "query_info", description = "查询天气、地图、交通等公共信息")
    public String queryInfo(
            @ToolParam(name = "query_type", description = "查询类型：weather/map/transport") String queryType,
            @ToolParam(name = "params", description = "查询参数JSON") String params) {
        logger.info("[TOOL][query_info] queryType={}, params={}", queryType, params);
        String result = String.format("{\"queryType\":\"%s\",\"params\":%s,\"result\":\"查询结果占位\"}", queryType, params);
        logger.debug("[TOOL][query_info] result={}", result);
        return result;
    }
}
