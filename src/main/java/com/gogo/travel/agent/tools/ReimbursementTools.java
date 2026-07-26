package com.gogo.travel.agent.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * @author Hollis
 */
@Component
public class ReimbursementTools {

    private static final Logger logger = LoggerFactory.getLogger(ReimbursementTools.class);

    @Tool(name = "ocr_invoice", description = "识别发票图片内容")
    public String ocrInvoice(
            @ToolParam(name = "image_url", description = "发票图片URL") String imageUrl) {

        return null;
    }

    @Tool(name = "generate_expense_report", description = "生成报销单")
    public String generateExpenseReport(
            @ToolParam(name = "invoices", description = "发票列表JSON") String invoices,
            @ToolParam(name = "trip_id", description = "关联行程ID") String tripId) {

        return null;
    }

    @Tool(name = "submit_reimbursement", description = "提交报销审批")
    public String submitReimbursement(
            @ToolParam(name = "report_id", description = "报销单ID") String reportId) {

        return null;
    }
}
