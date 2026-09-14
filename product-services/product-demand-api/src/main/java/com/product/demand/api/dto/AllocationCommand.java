package com.product.demand.api.dto;

import java.io.Serializable;
import java.util.List;

/**
 * 数量预占用命令（Phase 4 契约）。
 *
 * <p>语义与单体 OrderLineAllocationMapper.allocateQty/releaseQty 逐字对齐：</p>
 * <ul>
 *   <li>{@code mode=ALLOCATE}：{@code update order_line set allocated_qty = allocated_qty + delta
 *       where order_line_id = ? and allocated_qty + delta <= qty} —— 仅当预占不超需求量时生效
 *       （更新行数为 0 表示"订单行不存在/数量不足"，由调用方决定错误文案）；</li>
 *   <li>{@code mode=RELEASE}：无条件 {@code allocated_qty = allocated_qty + delta}
 *       （单体传负数 delta 做释放）。</li>
 * </ul>
 * <p>本命令为<b>写命令</b>：契约默认不自动重试（Retryer.NEVER_RETRY），由 demand 在
 * 本地事务内执行并 bump demand_data_version。</p>
 */
public class AllocationCommand implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final String MODE_ALLOCATE = "ALLOCATE";
    public static final String MODE_RELEASE = "RELEASE";

    private Long orderLineId;

    /** 正数预占/负数释放（与单体 mapper 参数一致）。 */
    private Long deltaQty;

    /** ALLOCATE / RELEASE。 */
    private String mode;

    public AllocationCommand() {
    }

    public AllocationCommand(Long orderLineId, Long deltaQty, String mode) {
        this.orderLineId = orderLineId;
        this.deltaQty = deltaQty;
        this.mode = mode;
    }

    public Long getOrderLineId() {
        return orderLineId;
    }

    public void setOrderLineId(Long orderLineId) {
        this.orderLineId = orderLineId;
    }

    public Long getDeltaQty() {
        return deltaQty;
    }

    public void setDeltaQty(Long deltaQty) {
        this.deltaQty = deltaQty;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    /** 预占用命令响应：updated=1 表示生效。 */
    public static class AllocationResponse implements Serializable {

        private static final long serialVersionUID = 1L;

        private int updated;

        public AllocationResponse() {
        }

        public AllocationResponse(int updated) {
            this.updated = updated;
        }

        public int getUpdated() {
            return updated;
        }

        public void setUpdated(int updated) {
            this.updated = updated;
        }
    }

    /** 按产品查订单行 ID 响应。 */
    public static class OrderLineIdsByProductResponse implements Serializable {

        private static final long serialVersionUID = 1L;

        private long snapshotVersion;

        private List<Long> orderLineIds;

        public long getSnapshotVersion() {
            return snapshotVersion;
        }

        public void setSnapshotVersion(long snapshotVersion) {
            this.snapshotVersion = snapshotVersion;
        }

        public List<Long> getOrderLineIds() {
            return orderLineIds;
        }

        public void setOrderLineIds(List<Long> orderLineIds) {
            this.orderLineIds = orderLineIds;
        }
    }

    /** 数据版本计数响应（漂移检测轻量端点）。 */
    public static class DataVersionResponse implements Serializable {

        private static final long serialVersionUID = 1L;

        private long snapshotVersion;

        public DataVersionResponse() {
        }

        public DataVersionResponse(long snapshotVersion) {
            this.snapshotVersion = snapshotVersion;
        }

        public long getSnapshotVersion() {
            return snapshotVersion;
        }

        public void setSnapshotVersion(long snapshotVersion) {
            this.snapshotVersion = snapshotVersion;
        }
    }
}
