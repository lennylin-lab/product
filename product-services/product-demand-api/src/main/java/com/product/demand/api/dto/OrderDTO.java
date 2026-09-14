package com.product.demand.api.dto;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单批量查询响应条目（Phase 4 契约，DUE_DATE_PRIORITY 排程策略输入）。
 */
public class OrderDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long orderId;

    /** 订单交期（排程优先级排序输入）。 */
    private LocalDateTime dueDate;

    /** 订单优先级（数值越大越紧急）。 */
    private Long priority;

    /** 订单状态。 */
    private String status;

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public LocalDateTime getDueDate() {
        return dueDate;
    }

    public void setDueDate(LocalDateTime dueDate) {
        this.dueDate = dueDate;
    }

    public Long getPriority() {
        return priority;
    }

    public void setPriority(Long priority) {
        this.priority = priority;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    /** 订单行批量查询响应信封。 */
    public static class OrderLineBatchResponse implements Serializable {

        private static final long serialVersionUID = 1L;

        /** demand_db 单调递增变更计数（demand_data_version 服务权属表），漂移检测用。 */
        private long snapshotVersion;

        private List<OrderLineDTO> orderLines;

        public long getSnapshotVersion() {
            return snapshotVersion;
        }

        public void setSnapshotVersion(long snapshotVersion) {
            this.snapshotVersion = snapshotVersion;
        }

        public List<OrderLineDTO> getOrderLines() {
            return orderLines;
        }

        public void setOrderLines(List<OrderLineDTO> orderLines) {
            this.orderLines = orderLines;
        }
    }

    /** 订单批量查询响应信封。 */
    public static class OrderBatchResponse implements Serializable {

        private static final long serialVersionUID = 1L;

        private long snapshotVersion;

        private List<OrderDTO> orders;

        public long getSnapshotVersion() {
            return snapshotVersion;
        }

        public void setSnapshotVersion(long snapshotVersion) {
            this.snapshotVersion = snapshotVersion;
        }

        public List<OrderDTO> getOrders() {
            return orders;
        }

        public void setOrders(List<OrderDTO> orders) {
            this.orders = orders;
        }
    }
}
