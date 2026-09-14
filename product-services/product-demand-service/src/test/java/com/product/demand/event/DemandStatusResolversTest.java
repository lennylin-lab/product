package com.product.demand.event;

import com.product.demand.common.constant.StatusConstants;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 订单行/订单状态聚合规则测试（单体 OrderLineStatusRefresher /
 * CustomerOrderStatusRefresher 冻结语义）。
 */
class DemandStatusResolversTest {

    @Test
    void allBatchesDoneShouldResolveLineDone() {
        assertEquals(StatusConstants.DONE_ORDER_LINE, DemandStatusResolvers.resolveOrderLineStatus(
                List.of(StatusConstants.DONE_PRODUCTION_BATCH, StatusConstants.DONE_PRODUCTION_BATCH),
                StatusConstants.IN_PRODUCTION_ORDER_LINE));
    }

    @Test
    void anyInProcessOrDoneBatchShouldResolveLineInProduction() {
        assertEquals(StatusConstants.IN_PRODUCTION_ORDER_LINE, DemandStatusResolvers.resolveOrderLineStatus(
                List.of(StatusConstants.IN_PROCESS_PRODUCTION_BATCH, StatusConstants.RELEASED_PRODUCTION_BATCH),
                StatusConstants.RELEASED_ORDER_LINE));
        // 单体冻结语义：DONE 与 RELEASED 并存仍为 IN_PRODUCTION（非全 DONE）
        assertEquals(StatusConstants.IN_PRODUCTION_ORDER_LINE, DemandStatusResolvers.resolveOrderLineStatus(
                List.of(StatusConstants.DONE_PRODUCTION_BATCH, StatusConstants.RELEASED_PRODUCTION_BATCH),
                StatusConstants.RELEASED_ORDER_LINE));
    }

    @Test
    void anyReleasedOrPlannedBatchShouldResolveLineReleased() {
        assertEquals(StatusConstants.RELEASED_ORDER_LINE, DemandStatusResolvers.resolveOrderLineStatus(
                List.of(StatusConstants.PLANNED_PRODUCTION_BATCH, StatusConstants.RELEASED_PRODUCTION_BATCH),
                StatusConstants.NEW_ORDER_LINE));
    }

    @Test
    void allLinesDoneShouldResolveOrderDone() {
        assertEquals(StatusConstants.DONE_CUSTOMER_ORDER, DemandStatusResolvers.resolveCustomerOrderStatus(
                List.of(StatusConstants.DONE_ORDER_LINE), StatusConstants.IN_PRODUCTION_CUSTOMER_ORDER));
    }

    @Test
    void anyInProductionOrDoneLineShouldResolveOrderInProduction() {
        assertEquals(StatusConstants.IN_PRODUCTION_CUSTOMER_ORDER, DemandStatusResolvers.resolveCustomerOrderStatus(
                List.of(StatusConstants.IN_PRODUCTION_ORDER_LINE, StatusConstants.RELEASED_ORDER_LINE),
                StatusConstants.CONFIRMED_CUSTOMER_ORDER));
    }

    @Test
    void anyReleasedLineShouldResolveOrderConfirmed() {
        assertEquals(StatusConstants.CONFIRMED_CUSTOMER_ORDER, DemandStatusResolvers.resolveCustomerOrderStatus(
                List.of(StatusConstants.RELEASED_ORDER_LINE, StatusConstants.NEW_ORDER_LINE),
                StatusConstants.CONFIRMED_CUSTOMER_ORDER));
    }
}
