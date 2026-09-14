package com.product.planning.controller;

import com.product.cloud.messaging.audit.OpsAuditService;
import com.product.cloud.messaging.deadletter.DeadLetterAuditor;
import com.product.cloud.messaging.outbox.OutboxMessage;
import com.product.cloud.messaging.replay.EventReplayService;
import com.product.planning.common.core.result.AjaxResult;
import com.product.planning.common.exception.ServiceException;
import com.product.planning.core.utils.SecurityUtils;
import com.product.planning.event.PlanningEventConsumerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 计划域运维端点（Phase 5：补偿/人工重放，全部动作写 ops_audit 留痕）。
 *
 * <ul>
 *   <li>{@code POST /internal/planning/ops/replay-outbox}：{"outboxId":123}——重放本域
 *       outbox 事件（batch.progress.changed；新 eventId + 原 correlationId/payload）；</li>
 *   <li>{@code POST /internal/planning/ops/replay-dlq}：{"auditId":123}——重放死信审计行
 *       （重投 execution.events，原 routing key = eventType）；</li>
 *   <li>{@code GET  /internal/planning/ops/health}：outbox 待投/HALTED、死信未重放计数；</li>
 *   <li>{@code POST /internal/planning/ops/recompute-batch-status}：{"batchId":123}——
 *       人工修正：按任务聚合重算批次状态（变更则发布 batch.progress.changed）。</li>
 * </ul>
 *
 * <p>鉴权（Phase 6 决策，随统一切换收口）：全部端点要求管理员权限
 * {@code @PreAuthorize("@ss.hasPermi('*:*:*')")}（token 内嵌 permissions 含 {@code *:*:*}，
 * 即 Identity 种子 admin 一类管理员；服务身份令牌 permissions 为空集，不可调用）。
 * 理由：ops 端点可重放事件/人工改写状态，等同生产操作，必须最小暴露——网关对
 * /internal/** 显式拒绝（仅内网直连可达），再加管理员门禁与 ops_audit 留痕双层约束；
 * 失败语义与单体权限契约一致（HTTP 200 + code 403 "没有权限，请联系管理员授权"）。</p>
 */
@Slf4j
@RestController
@ConditionalOnProperty(prefix = "product.messaging", name = "enabled", havingValue = "true")
@RequestMapping("/internal/planning/ops")
@RequiredArgsConstructor
@org.springframework.security.access.prepost.PreAuthorize("@ss.hasPermi('*:*:*')")
public class PlanningOpsController {

    private final EventReplayService eventReplayService;
    private final DeadLetterAuditor deadLetterAuditor;
    private final OpsAuditService opsAuditService;
    private final com.product.cloud.messaging.outbox.OutboxDao outboxDao;
    private final PlanningEventConsumerService consumerService;

    @PostMapping("/replay-outbox")
    public AjaxResult replayOutbox(@RequestBody Map<String, Object> body) {
        Long id = requireLong(body, "outboxId");
        if (id == null) {
            return AjaxResult.error("必须指定 outboxId");
        }
        try {
            String newEventId = eventReplayService.replayOutbox(id, operator());
            return AjaxResult.success("重放已入队", newEventId);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return AjaxResult.error(e.getMessage());
        }
    }

    @PostMapping("/replay-dlq")
    public AjaxResult replayDeadLetter(@RequestBody Map<String, Object> body) {
        Long id = requireLong(body, "auditId");
        if (id == null) {
            return AjaxResult.error("必须指定 auditId");
        }
        try {
            String newEventId = eventReplayService.replayDeadLetter(id, operator());
            return AjaxResult.success("死信已重放", newEventId);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return AjaxResult.error(e.getMessage());
        }
    }

    @GetMapping("/health")
    public AjaxResult health() {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String status : java.util.List.of(OutboxMessage.STATUS_PENDING,
                OutboxMessage.STATUS_PUBLISHED, OutboxMessage.STATUS_HALTED)) {
            counts.put(status, outboxDao.countByStatus(status));
        }
        result.put("outbox", counts);
        result.put("deadLetterUnreplayed", deadLetterAuditor.countUnreplayed());
        result.put("recentAudit", opsAuditService.findRecent(null, 10));
        return AjaxResult.success(result);
    }

    /** 人工修正：按任务聚合重算批次状态（补偿动作，审计留痕）。 */
    @PostMapping("/recompute-batch-status")
    public AjaxResult recomputeBatchStatus(@RequestBody Map<String, Object> body) {
        Long batchId = requireLong(body, "batchId");
        if (batchId == null) {
            return AjaxResult.error("必须指定 batchId");
        }
        boolean changed = consumerService.recomputeBatchStatus(batchId, null);
        opsAuditService.record("RECOMPUTE_BATCH_STATUS", operator(),
                "{\"batchId\":" + batchId + "}", changed ? "OK" : "NOOP",
                "人工重算批次状态: batchId=" + batchId + " changed=" + changed);
        return AjaxResult.success("已重算", Map.of("batchId", batchId, "changed", changed));
    }

    private Long requireLong(Map<String, Object> body, String field) {
        Object raw = body == null ? null : body.get(field);
        if (raw == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(raw));
        } catch (NumberFormatException e) {
            throw new ServiceException(field + " 非法: " + raw);
        }
    }

    private String operator() {
        try {
            String username = SecurityUtils.getUsername();
            return username == null || username.isBlank() ? "system" : username;
        } catch (Exception e) {
            return "system";
        }
    }
}
