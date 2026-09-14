package com.product.demand.controller;

import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.product.cloud.messaging.audit.OpsAuditService;
import com.product.cloud.messaging.deadletter.DeadLetterAuditor;
import com.product.cloud.messaging.outbox.OutboxMessage;
import com.product.cloud.messaging.replay.EventReplayService;
import com.product.demand.common.core.result.AjaxResult;
import com.product.demand.common.exception.ServiceException;
import com.product.demand.core.utils.SecurityUtils;
import com.product.demand.domain.entity.OrderLine;
import com.product.demand.event.DemandEventConsumerService;
import com.product.demand.event.DemandReconciliationService;
import com.product.demand.service.DemandDataVersionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 需求域运维端点（Phase 5：补偿/人工重放/人工修正，全部动作写 ops_audit 留痕）。
 *
 * <ul>
 *   <li>{@code POST /internal/demand/ops/replay-outbox}：{"outboxId":123}——重放本域
 *       outbox 事件（order_line.progress.changed）；</li>
 *   <li>{@code POST /internal/demand/ops/replay-dlq}：{"auditId":123}——重放死信审计行
 *       （重投 planning.events）；</li>
 *   <li>{@code POST /internal/demand/ops/adjust-allocation}：{"orderLineId":..,
 *       "newAllocatedQty":..}——人工修正已拆批数量（Phase 4 遗留"拆批先预占后落批"
 *       补偿窗口的人工收敛通道：对账脚本发现 allocated_qty 与 planning batch_qty 之和
 *       漂移后，由运维以正确值修正，幂等设值 + 审计）；</li>
 *   <li>{@code POST /internal/demand/ops/recompute-line-status}：{"orderLineId":..}——
 *       人工修正：按投影批次聚合重算订单行/订单状态；</li>
 *   <li>{@code GET  /internal/demand/ops/health}：outbox/死信/审计巡检。</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/internal/demand/ops")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "product.messaging", name = "enabled", havingValue = "true")
public class DemandOpsController {

    private final EventReplayService eventReplayService;
    private final DeadLetterAuditor deadLetterAuditor;
    private final OpsAuditService opsAuditService;
    private final com.product.cloud.messaging.outbox.OutboxDao outboxDao;
    private final DemandEventConsumerService consumerService;
    private final DemandReconciliationService reconciliationService;
    private final DemandDataVersionService demandDataVersionService;

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

    /**
     * 人工修正已拆批数量（Phase 4 补偿窗口收敛通道；幂等设值，审计留痕）。
     */
    @PostMapping("/adjust-allocation")
    public AjaxResult adjustAllocation(@RequestBody Map<String, Object> body) {
        Long orderLineId = requireLong(body, "orderLineId");
        Long newAllocatedQty = requireLong(body, "newAllocatedQty");
        if (orderLineId == null || newAllocatedQty == null) {
            return AjaxResult.error("必须指定 orderLineId 与 newAllocatedQty");
        }
        if (newAllocatedQty < 0) {
            return AjaxResult.error("newAllocatedQty 不能为负");
        }
        OrderLine line = Db.lambdaQuery(OrderLine.class)
                .eq(OrderLine::getOrderLineId, orderLineId)
                .one();
        if (line == null) {
            return AjaxResult.error("订单行不存在: " + orderLineId);
        }
        Long previous = line.getAllocatedQty();
        Db.lambdaUpdate(OrderLine.class)
                .set(OrderLine::getAllocatedQty, newAllocatedQty)
                .eq(OrderLine::getOrderLineId, orderLineId)
                .update();
        demandDataVersionService.bump();
        opsAuditService.record("ADJUST_ALLOCATION", operator(),
                toJson(Map.of("orderLineId", orderLineId, "newAllocatedQty", newAllocatedQty)),
                "OK", "已拆批数量人工修正: orderLineId=" + orderLineId
                        + " " + previous + "->" + newAllocatedQty);
        log.warn("allocated_qty 人工修正: orderLineId={} {}->{} operator={}",
                orderLineId, previous, newAllocatedQty, operator());
        return AjaxResult.success("已修正", Map.of(
                "orderLineId", orderLineId, "previous", previous == null ? 0 : previous,
                "allocatedQty", newAllocatedQty));
    }

    /** 人工修正：按投影批次聚合重算订单行状态（订单级随之重算）。 */
    @PostMapping("/recompute-line-status")
    public AjaxResult recomputeLineStatus(@RequestBody Map<String, Object> body) {
        Long orderLineId = requireLong(body, "orderLineId");
        if (orderLineId == null) {
            return AjaxResult.error("必须指定 orderLineId");
        }
        boolean changed = consumerService.refreshOrderLine(orderLineId, null);
        opsAuditService.record("RECOMPUTE_LINE_STATUS", operator(),
                "{\"orderLineId\":" + orderLineId + "}", changed ? "OK" : "NOOP",
                "人工重算订单行状态: orderLineId=" + orderLineId + " changed=" + changed);
        return AjaxResult.success("已重算", Map.of("orderLineId", orderLineId, "changed", changed));
    }

    /** 人工修正：按订单行聚合重算订单状态。 */
    @PostMapping("/recompute-order-status")
    public AjaxResult recomputeOrderStatus(@RequestBody Map<String, Object> body) {
        Long orderId = requireLong(body, "orderId");
        if (orderId == null) {
            return AjaxResult.error("必须指定 orderId");
        }
        boolean changed = reconciliationService.recomputeOrder(orderId);
        opsAuditService.record("RECOMPUTE_ORDER_STATUS", operator(),
                "{\"orderId\":" + orderId + "}", changed ? "OK" : "NOOP",
                "人工重算订单状态: orderId=" + orderId + " changed=" + changed);
        return AjaxResult.success("已重算", Map.of("orderId", orderId, "changed", changed));
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

    private static String toJson(Map<String, Object> map) {
        try {
            return com.product.cloud.messaging.codec.EnvelopeCodec.mapper().writeValueAsString(map);
        } catch (Exception e) {
            return String.valueOf(map);
        }
    }
}
