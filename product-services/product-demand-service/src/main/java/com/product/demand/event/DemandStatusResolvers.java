package com.product.demand.event;

import com.product.demand.common.constant.StatusConstants;

import java.util.List;

/**
 * 订单行/客户订单状态聚合推导（单体 product-demand OrderLineStatusRefresher /
 * CustomerOrderStatusRefresher 的服务化移植，状态机语义冻结）：
 *
 * <pre>
 * 订单行（输入 = 全部批次状态）:
 *   DONE_ORDER_LINE           ← 所有批次均为 DONE
 *   IN_PRODUCTION_ORDER_LINE  ← 存在 IN_PROCESS 或 DONE 批次
 *   RELEASED_ORDER_LINE       ← 存在 RELEASED 或 PLANNED 批次
 *   其余保持 currentStatus
 * 客户订单（输入 = 全部订单行状态）:
 *   DONE_CUSTOMER_ORDER           ← 所有订单行均为 DONE
 *   IN_PRODUCTION_CUSTOMER_ORDER  ← 存在 IN_PRODUCTION 或 DONE 订单行
 *   CONFIRMED_CUSTOMER_ORDER      ← 存在 RELEASED 订单行
 *   其余保持 currentStatus
 * </pre>
 */
public final class DemandStatusResolvers {

    private DemandStatusResolvers() {
    }

    /** 聚合推导订单行目标状态：全完成→DONE，存在执行中或已完成→IN_PRODUCTION，存在已释放→RELEASED。 */
    public static String resolveOrderLineStatus(List<String> batchStatuses, String currentStatus) {
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

    /** 聚合推导客户订单目标状态：全完成→DONE，存在生产中或已完成→IN_PRODUCTION，存在已释放→CONFIRMED。 */
    public static String resolveCustomerOrderStatus(List<String> lineStatuses, String currentStatus) {
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
