package com.product.demand.status;

import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.product.common.constant.StatusConstants;
import com.product.core.status.StatusEntityType;
import com.product.core.status.StatusRefreshContext;
import com.product.core.status.StatusRefresher;
import com.product.domain.entity.OrderLine;
import com.product.domain.entity.ProductionBatch;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 订单行状态刷新器。
 *
 * <p>根据关联的 {@link ProductionBatch} 状态聚合推导订单行状态，状态流转方向为单向递进：</p>
 * <pre>
 *   RELEASED_ORDER_LINE ← 批次处于 PLANNED/RELEASED
 *   IN_PRODUCTION_ORDER_LINE ← 批次处于 IN_PROCESS 或 DONE
 *   DONE_ORDER_LINE ← 所有批次均为 DONE
 * </pre>
 *
 * <p>触发场景：下游（生产批次或工序任务）状态变更后，由 {@link com.product.core.status.StatusRefreshService} 级联调用。</p>
 */
@Component
public class OrderLineStatusRefresher implements StatusRefresher {

    @Override
    public int getOrder() {
        return StatusEntityType.ORDER_LINE.getOrder();
    }

    @Override
    public void refresh(StatusRefreshContext context) {
        if (context == null) {
            return;
        }
        List<Long> orderLineIds = resolveOrderLineIds(context);
        for (Long orderLineId : orderLineIds) {
            refreshOrderLine(orderLineId);
        }
    }

    /** 从刷新上下文中解析需要刷新的订单行ID：直接指定订单行时取其ID，指定生产批次时反查关联的订单行ID */
    private List<Long> resolveOrderLineIds(StatusRefreshContext context) {
        if (context.getEntityType() == StatusEntityType.ORDER_LINE) {
            return context.getIds().stream()
                    .filter(Objects::nonNull)
                    .map(String::valueOf)
                    .map(Long::valueOf)
                    .collect(Collectors.toList());
        }
        if (context.getEntityType() != StatusEntityType.PRODUCTION_BATCH) {
            return List.of();
        }
        return Db.lambdaQuery(ProductionBatch.class)
                .select(ProductionBatch::getOrderLineId)
                .in(ProductionBatch::getBatchId, context.getIds().stream().filter(Objects::nonNull).map(String::valueOf).collect(Collectors.toList()))
                .list()
                .stream()
                .map(ProductionBatch::getOrderLineId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
    }

    /** 刷新单个订单行状态：汇总其下所有批次状态，仅在目标状态与当前不同时才写入 */
    private void refreshOrderLine(Long orderLineId) {
        OrderLine orderLine = Db.lambdaQuery(OrderLine.class)
                .select(OrderLine::getOrderLineId, OrderLine::getStatus)
                .eq(OrderLine::getOrderLineId, orderLineId)
                .last("limit 1")
                .one();
        if (orderLine == null) {
            return;
        }
        List<String> batchStatuses = Db.lambdaQuery(ProductionBatch.class)
                .select(ProductionBatch::getStatus)
                .eq(ProductionBatch::getOrderLineId, orderLineId)
                .list()
                .stream()
                .filter(Objects::nonNull)
                .map(ProductionBatch::getStatus)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (CollectionUtils.isEmpty(batchStatuses)) {
            return;
        }
        String targetStatus = resolveOrderLineStatus(batchStatuses, orderLine.getStatus());
        if (!Objects.equals(orderLine.getStatus(), targetStatus)) {
            Db.lambdaUpdate(OrderLine.class)
                    .set(OrderLine::getStatus, targetStatus)
                    .eq(OrderLine::getOrderLineId, orderLineId)
                    .update();
        }
    }

    /** 聚合推导订单行目标状态：全完成→DONE，存在执行中或已完成→IN_PRODUCTION，存在已释放→RELEASED */
    private String resolveOrderLineStatus(List<String> batchStatuses, String currentStatus) {
        if (batchStatuses.stream().allMatch(StatusConstants.DONE_PRODUCTION_BATCH::equals)) {
            return StatusConstants.DONE_ORDER_LINE;
        }
        boolean hasInProcess = batchStatuses.stream().anyMatch(status ->
                StatusConstants.IN_PROCESS_PRODUCTION_BATCH.equals(status)
                        || StatusConstants.DONE_PRODUCTION_BATCH.equals(status));
        if (hasInProcess) {
            return StatusConstants.IN_PRODUCTION_ORDER_LINE;
        }
        boolean hasReleased = batchStatuses.stream().anyMatch(status ->
                StatusConstants.RELEASED_PRODUCTION_BATCH.equals(status)
                        || StatusConstants.PLANNED_PRODUCTION_BATCH.equals(status));
        if (hasReleased) {
            return StatusConstants.RELEASED_ORDER_LINE;
        }
        return currentStatus;
    }
}
