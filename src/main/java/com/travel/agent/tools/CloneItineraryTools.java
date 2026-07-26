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
public class CloneItineraryTools {

    private static final Logger logger = LoggerFactory.getLogger(CloneItineraryTools.class);

    @Tool(name = "clone_itinerary", description = "基于历史行程克隆新行程")
    public String cloneItinerary(
            @ToolParam(name = "source", description = "来源行程标识") String source,
            @ToolParam(name = "new_departure_date", description = "新出发日期") String newDepartureDate,
            @ToolParam(name = "adjust_mode", description = "调整模式：KEEP_RELATIVE_TIME/EXACT_TIME") String adjustMode) {
        //todo
        return null;
    }
}
