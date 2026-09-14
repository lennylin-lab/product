package com.product.demand.api.dto;

import java.io.Serializable;

/**
 * 订单行批量查询响应条目（Phase 4 契约）。
 *
 * <p>字段为排程/拆批跨域消费所需的最小集（order_line 表同名列）：
 * {@code version} 为行级数据版本（update_time epoch 毫秒，null 记 0），
 * 与 product-master-data-api 的行级版本方案一致（baselines.md §2 补充）。</p>
 */
public class OrderLineDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long orderLineId;

    private Long orderId;

    private Long productId;

    /** 需求数量。 */
    private Long qty;

    /** 已拆批预占数量。 */
    private Long allocatedQty;

    /** 订单行状态（NEW/RELEASED/IN_PRODUCTION/DONE）。 */
    private String status;

    /** 行级数据版本：update_time epoch 毫秒（null 记 0）。 */
    private long version;

    public Long getOrderLineId() {
        return orderLineId;
    }

    public void setOrderLineId(Long orderLineId) {
        this.orderLineId = orderLineId;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public Long getQty() {
        return qty;
    }

    public void setQty(Long qty) {
        this.qty = qty;
    }

    public Long getAllocatedQty() {
        return allocatedQty;
    }

    public void setAllocatedQty(Long allocatedQty) {
        this.allocatedQty = allocatedQty;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }
}
