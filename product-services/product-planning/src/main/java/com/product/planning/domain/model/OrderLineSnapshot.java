package com.product.planning.domain.model;

import lombok.Data;

/**
 * 订单行快照视图（Phase 4）：order_line 归 product-demand（ADR-0005），排程经
 * DemandBatchQueryApi.order-lines/batch 加载后映射（字段仅覆盖排程消费面）。
 */
@Data
public class OrderLineSnapshot {

    private Long orderLineId;

    private Long orderId;

    private Long productId;

    private Long qty;

    private Long allocatedQty;

    private String status;
}
