package com.product.planning.domain.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 订单快照视图（Phase 4）：customer_order 归 product-demand（ADR-0005），排程经
 * DemandBatchQueryApi.orders/batch 加载后映射（交期/优先级为 DUE_DATE_PRIORITY 策略输入）。
 */
@Data
public class OrderSnapshot {

    private Long orderId;

    /** 订单交期。 */
    private LocalDateTime dueDate;

    /** 订单优先级（数值越大越紧急）。 */
    private Long priority;

    private String status;
}
