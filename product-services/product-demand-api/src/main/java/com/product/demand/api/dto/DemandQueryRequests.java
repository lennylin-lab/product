package com.product.demand.api.dto;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 需求域批量查询请求/响应载体（Phase 4 契约）。
 *
 * <p>批量 ID 请求统一约束：单次上限 {@link #MAX_IDS}=1000、非空时去重校验
 * （与 product-master-data-api 同规则）。</p>
 */
public final class DemandQueryRequests {

    public static final int MAX_IDS = 1000;

    private DemandQueryRequests() {
    }

    /** 订单行批量查询请求（orderLineIds 为 null/空 = 全量）。 */
    public static class OrderLineBatchQueryRequest implements Serializable {

        private static final long serialVersionUID = 1L;

        private List<Long> orderLineIds;

        public OrderLineBatchQueryRequest() {
        }

        public OrderLineBatchQueryRequest(List<Long> orderLineIds) {
            this.orderLineIds = orderLineIds;
        }

        public List<Long> getOrderLineIds() {
            return orderLineIds;
        }

        public void setOrderLineIds(List<Long> orderLineIds) {
            this.orderLineIds = orderLineIds;
        }
    }

    /** 订单批量查询请求（orderIds 为 null/空 = 全量）。 */
    public static class OrderBatchQueryRequest implements Serializable {

        private static final long serialVersionUID = 1L;

        private List<Long> orderIds;

        public OrderBatchQueryRequest() {
        }

        public OrderBatchQueryRequest(List<Long> orderIds) {
            this.orderIds = orderIds;
        }

        public List<Long> getOrderIds() {
            return orderIds;
        }

        public void setOrderIds(List<Long> orderIds) {
            this.orderIds = orderIds;
        }
    }

    /** 按产品查订单行 ID 请求（产品 ID 非空必填；master-data 启用路线删除保护链路）。 */
    public static class OrderLineIdsByProductRequest implements Serializable {

        private static final long serialVersionUID = 1L;

        private Long productId;

        public OrderLineIdsByProductRequest() {
        }

        public OrderLineIdsByProductRequest(Long productId) {
            this.productId = productId;
        }

        public Long getProductId() {
            return productId;
        }

        public void setProductId(Long productId) {
            this.productId = productId;
        }
 }
}
