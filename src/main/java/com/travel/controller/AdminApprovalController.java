package com.travel.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.session.AgentSession;
import com.travel.agent.session.AgentSessionManager;
import com.travel.business.approval.entity.ApprovalRecord;
import com.travel.business.approval.entity.ApprovalRecordStatus;
import com.travel.business.approval.service.ApprovalService;
import com.travel.business.auth.service.AuthService;
import com.travel.business.order.entity.TravelOrderStatus;
import com.travel.business.order.repo.TravelOrderRepository;
import com.travel.controller.request.ApprovalDecisionRequest;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 后台管理员审批接口。
 * <p>仅 role = ADMIN 的管理员用户可访问，用于查看待审批的审批单并做出通过/拒绝决策。
 *
 * @author Hollis
 */
@RestController
@RequestMapping("/api/admin")
@CrossOrigin(origins = "*")
public class AdminApprovalController {

    private static final Logger logger = LoggerFactory.getLogger(AdminApprovalController.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private AuthService authService;
    @Autowired
    private ApprovalService approvalService;
    @Autowired
    private TravelOrderRepository travelOrderRepository;
    @Autowired
    private AgentSessionManager sessionManager;

    /**
     * 查询审批单列表。
     * @param status 状态过滤（PENDING/APPROVED/REJECTED/CANCELLED），为空则返回全部
     */
    @GetMapping("/approvals")
    @SaCheckLogin
    public ResponseEntity<?> listApprovals(@RequestParam(required = false) String status) {
        if (!isAdmin()) {
            return forbidden();
        }
        ApprovalRecordStatus statusFilter = ApprovalRecordStatus.of(status);
        List<ApprovalRecord> records = approvalService.list(statusFilter);
        List<Map<String, Object>> result = new ArrayList<>();
        for (ApprovalRecord record : records) {
            result.add(toView(record));
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 管理员对审批单做出决策：通过或拒绝。
     */
    @PostMapping("/approvals/{processInstanceId}/decision")
    @SaCheckLogin
    public ResponseEntity<?> decide(@PathVariable String processInstanceId,
                                    @RequestBody ApprovalDecisionRequest request) {
        if (!isAdmin()) {
            return forbidden();
        }
        boolean agree = "agree".equalsIgnoreCase(request.getDecision());
        boolean refuse = "refuse".equalsIgnoreCase(request.getDecision());
        if (!agree && !refuse) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "decision 只能为 agree 或 refuse"));
        }

        Optional<ApprovalRecord> updated = approvalService.decide(processInstanceId, agree, request.getRemark());
        if (updated.isEmpty()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("message", "审批单不存在或已被处理"));
        }
        ApprovalRecord record = updated.get();

        // 同步差旅单状态
        if (record.getOrderId() != null && !record.getOrderId().isBlank()) {
            TravelOrderStatus orderStatus = agree ? TravelOrderStatus.APPROVED : TravelOrderStatus.REJECTED;
            travelOrderRepository.updateStatus(record.getOrderId(), orderStatus);
        }

        // 恢复挂起的 Agent 会话（复用钉钉回调的 resume 流程）
        resumeSession(processInstanceId, agree, request.getRemark());

        return ResponseEntity.ok(toView(record));
    }

    /** 恢复挂起在该审批单上的 Agent 会话 */
    private void resumeSession(String processInstanceId, boolean agree, String remark) {
        AgentSession session = sessionManager.getByApprovalId(processInstanceId);
        if (session == null) {
            return;
        }
        String resultJson = agree
                ? String.format("{\"status\":\"APPROVED\",\"processInstanceId\":\"%s\"}", processInstanceId)
                : String.format("{\"status\":\"REJECTED\",\"reason\":\"%s\",\"processInstanceId\":\"%s\"}",
                        remark != null ? remark : "", processInstanceId);
        Msg resumeMsg = Msg.builder()
                .role(MsgRole.USER)
                .name("system")
                .content(TextBlock.builder()
                        .text("管理员审批结果：" + resultJson)
                        .build())
                .build();
        session.getAgent().call(List.of(resumeMsg)).subscribe();
    }

    /** 将审批单转换为前端视图对象，审批表单解析为结构化对象 */
    private Map<String, Object> toView(ApprovalRecord record) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("processInstanceId", record.getProcessInstanceId());
        view.put("userId", record.getUserId());
        view.put("userName", authService.getRealName(record.getUserId()));
        view.put("title", record.getTitle());
        view.put("status", record.getStatus() != null ? record.getStatus().getCode() : null);
        view.put("statusLabel", record.getStatus() != null ? record.getStatus().getLabel() : null);
        view.put("orderId", record.getOrderId());
        view.put("remark", record.getRemark());
        view.put("submitTime", record.getSubmitTime() != null ? record.getSubmitTime().toEpochMilli() : null);
        view.put("updateTime", record.getUpdateTime() != null ? record.getUpdateTime().toEpochMilli() : null);
        view.put("form", parseForm(record.getApprovalForm()));
        return view;
    }

    /** 安全解析审批表单 JSON，失败时返回 null */
    private Object parseForm(String approvalForm) {
        if (approvalForm == null || approvalForm.isBlank()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(approvalForm, Map.class);
        } catch (Exception e) {
            logger.warn("[AdminApprovalController] 审批表单解析失败: {}", e.getMessage());
            return approvalForm;
        }
    }

    private boolean isAdmin() {
        return authService.isAdmin(StpUtil.getLoginIdAsString());
    }

    private ResponseEntity<?> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("message", "无权访问后台管理接口"));
    }
}
