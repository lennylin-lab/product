package com.product.demand.status;

import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.product.common.constant.StatusConstants;
import com.product.core.status.StatusEntityType;
import com.product.core.status.StatusRefreshContext;
import com.product.core.status.StatusRefresher;
import com.product.domain.entity.CustomerOrder;
import com.product.domain.entity.OrderLine;
import com.product.domain.entity.ProductionBatch;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 客户订单状态刷新器。
 *
 * <p>根据关联的 {@link OrderLine} 状态聚合推导客户订单状态，状态流转方向为单向递进：</p>
 * <pre>
 *   CONFIRMED_CUSTOMER_ORDER ← 存在已释放的订单行
 *   IN_PRODUCTION_CUSTOMER_ORDER ← 存在生产中或已完成的订单行
 *   DONE_CUSTOMER_ORDER ← 所有订单行均已完成
 * </pre>
 *
 * <p>触发场景：下游（订单行、生产批次、工序任务）状态变更后，由 {@link com.product.core.status.StatusRefreshService} 级联调用。</p>
 */
@Component
public class CustomerOrderStatusRefresher implements StatusRefresher {

    @Override
    public int getOrder() {
        return StatusEntityType.CUSTOMER_ORDER.getOrder();
    }

    @Override
    public void refresh(StatusRefreshContext context) {
        if (context == null) {
            return;
        }
        List<String> orderIds = resolveOrderIds(context);
        for (String orderId : orderIds) {
            refreshCustomerOrder(orderId);
        }
    }

    /** 从刷新上下文中解析需要刷新的订单ID：支持直接指定订单、通过订单行反查、通过生产批次逐级反查 */
    private List<String> resolveOrderIds(StatusRefreshContext context) {
        if (context.getEntityType() == StatusEntityType.CUSTOMER_ORDER) {
            return context.getIds().stream()
                    .filter(Objects::nonNull)
                    .map(String::valueOf)
                    .distinct()
                    .collect(Collectors.toList());
        }
        List<Long> orderLineIds;
        if (context.getEntityType() == StatusEntityType.ORDER_LINE) {
            orderLineIds = context.getIds().stream()
                    .filter(Objects::nonNull)
                    .map(String::valueOf)
                    .map(Long::valueOf)
                    .collect(Collectors.toList());
        } else if (context.getEntityType() == StatusEntityType.PRODUCTION_BATCH) {
            orderLineIds = Db.lambdaQuery(ProductionBatch.class)
                    .select(ProductionBatch::getOrderLineId)
                    .in(ProductionBatch::getBatchId, context.getIds().stream().filter(Objects::nonNull).map(String::valueOf).collect(Collectors.toList()))
                    .list()
                    .stream()
                    .map(ProductionBatch::getOrderLineId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .collect(Collectors.toList());
        } else {
            return List.of();
        }
        if (CollectionUtils.isEmpty(orderLineIds)) {
            return List.of();
        }
        return Db.lambdaQuery(OrderLine.class)
                .select(OrderLine::getOrderId)
                .in(OrderLine::getOrderLineId, orderLineIds)
                .list()
                .stream()
                .map(OrderLine::getOrderId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
    }

    /** 刷新单个客户订单状态：汇总其下所有订单行状态，仅在目标状态与当前不同时才写入 */
    private void refreshCustomerOrder(String orderId) {
        CustomerOrder customerOrder = Db.lambdaQuery(CustomerOrder.class)
                .select(CustomerOrder::getOrderId, CustomerOrder::getStatus)
                .eq(CustomerOrder::getOrderId, orderId)
                .last("limit 1")
                .one();
        if (customerOrder == null) {
            return;
        }
        List<String> lineStatuses = Db.lambdaQuery(OrderLine.class)
                .select(OrderLine::getStatus)
                .eq(OrderLine::getOrderId, orderId)
                .list()
                .stream()
                .filter(Objects::nonNull)
                .map(OrderLine::getStatus)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (CollectionUtils.isEmpty(lineStatuses)) {
            return;
        }
        String targetStatus = resolveCustomerOrderStatus(lineStatuses, customerOrder.getStatus());
        if (!Objects.equals(customerOrder.getStatus(), targetStatus)) {
            Db.lambdaUpdate(CustomerOrder.class)
                    .set(CustomerOrder::getStatus, targetStatus)
                    .eq(CustomerOrder::getOrderId, orderId)
                    .update();
        }
    }

    /** 聚合推导客户订单目标状态：全完成→DONE，存在生产中或已完成→IN_PRODUCTION，存在已释放→CONFIRMED */
    private String resolveCustomerOrderStatus(List<String> lineStatuses, String currentStatus) {
        if (lineStatuses.stream().allMatch(StatusConstants.DONE_ORDER_LINE::equals)) {
            return StatusConstants.DONE_CUSTOMER_ORDER;
        }
        boolean hasInProduction = lineStatuses.stream().anyMatch(status ->
                StatusConstants.IN_PRODUCTION_ORDER_LINE.equals(status)
                        || StatusConstants.DONE_ORDER_LINE.equals(status));
        if (hasInProduction) {
            return StatusConstants.IN_PRODUCTION_CUSTOMER_ORDER;
        }
        boolean hasReleased = lineStatuses.stream().anyMatch(StatusConstants.RELEASED_ORDER_LINE::equals);
        if (hasReleased) {
            return StatusConstants.CONFIRMED_CUSTOMER_ORDER;
        }
        return currentStatus;
    }
}
