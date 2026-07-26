package com.gogo.travel.controller;

import com.gogo.travel.business.approval.service.ApprovalService;
import com.gogo.travel.agent.session.AgentSession;
import com.gogo.travel.agent.session.AgentSessionManager;
import com.gogo.travel.controller.request.DingTalkCallbackEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author Hollis
 */
@RestController
@RequestMapping("/api/callback")
public class ApprovalCallbackController {

    @Autowired
    private AgentSessionManager sessionManager;
    @Autowired
    private ApprovalService approvalService;

    @PostMapping("/dingtalk/approval")
    public String onApprovalEvent(@RequestBody DingTalkCallbackEvent event) {
        String instanceId = event.getProcessInstanceId();
        String result = event.getResult();

        approvalService.updateStatus(instanceId, result);

        AgentSession session = sessionManager.getByApprovalId(instanceId);
        if (session != null) {
            String resultJson = "agree".equalsIgnoreCase(result)
                    ? String.format("{\"status\":\"APPROVED\",\"processInstanceId\":\"%s\"}", instanceId)
                    : String.format("{\"status\":\"REJECTED\",\"reason\":\"%s\",\"processInstanceId\":\"%s\"}",
                            event.getRemark() != null ? event.getRemark() : "", instanceId);

            // TODO: 通过 Hook 捕获 PostActingEvent 获取 toolUseId 后，可改为 MsgRole.TOOL 的 ToolResultBlock 恢复
            Msg resumeMsg = Msg.builder()
                    .role(MsgRole.USER)
                    .name("system")
                    .content(TextBlock.builder()
                            .text("钉钉审批回调：" + resultJson)
                            .build())
                    .build();

            session.getAgent().call(List.of(resumeMsg)).subscribe();
        }

        return "success";
    }
}
