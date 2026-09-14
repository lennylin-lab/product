package com.product.demand.event;

import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.product.cloud.messaging.audit.OpsAuditService;
import com.product.demand.domain.entity.CustomerOrder;
import com.product.demand.domain.entity.OrderLine;
import com.product.demand.domain.entity.PlanningBatchState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 需求域状态对账（Phase 5，ADR-0004 §6；implement.md Phase 5 范围 4）。
 *
 * <p>本域不变式（全部本地数据）：订单行状态 = 其投影批次状态聚合（DemandStatusResolvers
 * 冻结规则）、客户订单状态 = 其订单行状态聚合。发现漂移 → ops_audit 留痕（RECON_DRIFT）
 * → 自动修复（重算 + 必要时出站事件，RECON_HEAL）。</p>
 *
 * <p>投影本身 vs planning 事实、跨域任务事件流水、allocated_qty vs batch_qty 等
 * 跨域对账由运维对账脚本执行（scratch/phase5/recon.py）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "product.messaging", name = "enabled", havingValue = "true")
public class DemandReconciliationService {

    private final DemandEventConsumerService consumerService;
    private final OpsAuditService opsAuditService;
    private final com.product.demand.service.DemandDataVersionService demandDataVersionService;

    /** 本域对账（默认 60s 一轮）。 */
    @Scheduled(fixedDelayString = "${product.demand.recon.interval-ms:60000}",
            initialDelayString = "${product.demand.recon.initial-delay-ms:20000}")
    public void reconcile() {
        List<OrderLine> lines = Db.lambdaQuery(OrderLine.class).list();
        List<Long> driftedLines = new ArrayList<>();
        List<Long> driftedOrders = new ArrayList<>();
        for (OrderLine line : lines) {
            String expected = expectedLineStatus(line);
            if (expected != null && !Objects.equals(expected, line.getStatus())) {
                driftedLines.add(line.getOrderLineId());
            }
        }
        for (CustomerOrder order : Db.lambdaQuery(CustomerOrder.class)
                .select(CustomerOrder::getOrderId, CustomerOrder::getStatus).list()) {
            String expected = expectedOrderStatus(order.getOrderId(), order.getStatus());
            if (expected != null && !Objects.equals(expected, order.getStatus())) {
                driftedOrders.add(order.getOrderId());
            }
        }
        if (driftedLines.isEmpty() && driftedOrders.isEmpty()) {
            return;
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("domain", "demand");
        params.put("invariant", "line.status = aggregate(batch projection), order.status = aggregate(line.status)");
        params.put("driftedLineIds", driftedLines);
        params.put("driftedOrderIds", driftedOrders);
        opsAuditService.record("RECON_DRIFT", "system", toJson(params), "DRIFT",
                "订单行/订单状态与聚合不一致: lines=" + driftedLines + " orders=" + driftedOrders);
        for (Long lineId : driftedLines) {
            healLine(lineId);
        }
        // 订单级漂移（行聚合已一致但订单滞后）：直接按行重算订单
        for (Long orderId : driftedOrders) {
            healOrder(orderId);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void healLine(Long orderLineId) {
        try {
            boolean changed = consumerService.refreshOrderLine(orderLineId, null);
            opsAuditService.record("RECON_HEAL", "system", "{\"orderLineId\":" + orderLineId + "}",
                    changed ? "OK" : "NOOP", "订单行状态重算: orderLineId=" + orderLineId);
        } catch (Exception e) {
            log.warn("订单行状态修复失败（下轮对账重试）: orderLineId={} err={}", orderLineId, e.getMessage());
            opsAuditService.record("RECON_HEAL", "system", "{\"orderLineId\":" + orderLineId + "}",
                    "FAIL", e.getMessage() == null ? "unknown" : e.getMessage());
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void healOrder(Long orderId) {
        try {
            boolean changed = recomputeOrder(orderId);
            opsAuditService.record("RECON_HEAL", "system", "{\"orderId\":" + orderId + "}",
                    changed ? "OK" : "NOOP", "订单状态重算: orderId=" + orderId);
        } catch (Exception e) {
            log.warn("订单状态修复失败（下轮对账重试）: orderId={} err={}", orderId, e.getMessage());
            opsAuditService.record("RECON_HEAL", "system", "{\"orderId\":" + orderId + "}",
                    "FAIL", e.getMessage() == null ? "unknown" : e.getMessage());
        }
    }

    /** 按行重算订单状态（变更时落库 + bump；独立事务供对账/人工修正共用）。 */
    @Transactional(rollbackFor = Exception.class)
    public boolean recomputeOrder(Long orderId) {
        CustomerOrder order = Db.lambdaQuery(CustomerOrder.class)
                .eq(CustomerOrder::getOrderId, orderId)
                .one();
        if (order == null) {
            return false;
        }
        List<String> lineStatuses = Db.lambdaQuery(OrderLine.class)
                .select(OrderLine::getStatus)
                .eq(OrderLine::getOrderId, orderId)
                .list()
                .stream()
                .map(OrderLine::getStatus)
                .filter(Objects::nonNull)
                .toList();
        if (lineStatuses.isEmpty()) {
            return false;
        }
        String target = DemandStatusResolvers.resolveCustomerOrderStatus(lineStatuses, order.getStatus());
        if (Objects.equals(target, order.getStatus())) {
            return false;
        }
        Db.lambdaUpdate(CustomerOrder.class)
                .set(CustomerOrder::getStatus, target)
                .eq(CustomerOrder::getOrderId, orderId)
                .update();
        demandDataVersionService.bump();
        return true;
    }

    /** 订单行期望状态（无批次投影 → null，表示无判据，不动）。 */
    private String expectedLineStatus(OrderLine line) {
        List<String> batchStatuses = Db.lambdaQuery(PlanningBatchState.class)
                .select(PlanningBatchState::getStatus)
                .eq(PlanningBatchState::getOrderLineId, line.getOrderLineId())
                .list()
                .stream()
                .map(PlanningBatchState::getStatus)
                .filter(Objects::nonNull)
                .toList();
        if (batchStatuses.isEmpty()) {
            return null;
        }
        return DemandStatusResolvers.resolveOrderLineStatus(batchStatuses, line.getStatus());
    }

    /** 订单期望状态（无订单行 → null）。 */
    private String expectedOrderStatus(Long orderId, String currentStatus) {
        List<String> lineStatuses = Db.lambdaQuery(OrderLine.class)
                .select(OrderLine::getStatus)
                .eq(OrderLine::getOrderId, orderId)
                .list()
                .stream()
                .map(OrderLine::getStatus)
                .filter(Objects::nonNull)
                .toList();
        if (lineStatuses.isEmpty()) {
            return null;
        }
        return DemandStatusResolvers.resolveCustomerOrderStatus(lineStatuses, currentStatus);
    }

    private static String toJson(Map<String, Object> map) {
        try {
            return com.product.cloud.messaging.codec.EnvelopeCodec.mapper().writeValueAsString(map);
        } catch (Exception e) {
            return String.valueOf(map);
        }
    }
}
