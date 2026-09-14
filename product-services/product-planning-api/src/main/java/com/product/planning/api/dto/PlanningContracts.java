package com.product.planning.api.dto;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 计划排程域契约 DTO 载体（Phase 4）。
 */
public final class PlanningContracts {

    private PlanningContracts() {
    }

    /** 生产批次只读视图（与 demand 侧 ProductionBatchView 字段一致；非持久化模型）。 */
    public static class ProductionBatchViewDTO implements Serializable {

        private static final long serialVersionUID = 1L;

        private Long batchId;

        private Long orderLineId;

        private Long batchQty;

        private String status;

        private LocalDateTime plannedStart;

        private LocalDateTime plannedEnd;

        private LocalDateTime createTime;

        private LocalDateTime updateTime;

        public Long getBatchId() {
            return batchId;
        }

        public void setBatchId(Long batchId) {
            this.batchId = batchId;
        }

        public Long getOrderLineId() {
            return orderLineId;
        }

        public void setOrderLineId(Long orderLineId) {
            this.orderLineId = orderLineId;
        }

        public Long getBatchQty() {
            return batchQty;
        }

        public void setBatchQty(Long batchQty) {
            this.batchQty = batchQty;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public LocalDateTime getPlannedStart() {
            return plannedStart;
        }

        public void setPlannedStart(LocalDateTime plannedStart) {
            this.plannedStart = plannedStart;
        }

        public LocalDateTime getPlannedEnd() {
            return plannedEnd;
        }

        public void setPlannedEnd(LocalDateTime plannedEnd) {
            this.plannedEnd = plannedEnd;
        }

        public LocalDateTime getCreateTime() {
            return createTime;
        }

        public void setCreateTime(LocalDateTime createTime) {
            this.createTime = createTime;
        }

        public LocalDateTime getUpdateTime() {
            return updateTime;
        }

        public void setUpdateTime(LocalDateTime updateTime) {
            this.updateTime = updateTime;
        }
    }

    /** 按订单行批量取批次请求（orderLineIds 非空必填、去重、上限 1000）。 */
    public static class BatchesByOrderLinesRequest implements Serializable {

        private static final long serialVersionUID = 1L;

        public static final int MAX_IDS = 1000;

        private List<Long> orderLineIds;

        public BatchesByOrderLinesRequest() {
        }

        public BatchesByOrderLinesRequest(List<Long> orderLineIds) {
            this.orderLineIds = orderLineIds;
        }

        public List<Long> getOrderLineIds() {
            return orderLineIds;
        }

        public void setOrderLineIds(List<Long> orderLineIds) {
            this.orderLineIds = orderLineIds;
        }
    }

    /** 按订单行批量取批次响应。 */
    public static class BatchesByOrderLinesResponse implements Serializable {

        private static final long serialVersionUID = 1L;

        private List<ProductionBatchViewDTO> batches;

        public List<ProductionBatchViewDTO> getBatches() {
            return batches;
        }

        public void setBatches(List<ProductionBatchViewDTO> batches) {
            this.batches = batches;
        }
    }

    /** 按订单行删除批次响应（deleted = 实际删除行数）。 */
    public static class DeletedBatchesResponse implements Serializable {

        private static final long serialVersionUID = 1L;

        private int deleted;

        public DeletedBatchesResponse() {
        }

        public DeletedBatchesResponse(int deleted) {
            this.deleted = deleted;
        }

        public int getDeleted() {
            return deleted;
        }

        public void setDeleted(int deleted) {
            this.deleted = deleted;
        }
    }

    /** 阻塞任务判断响应（存在 READY/SCHEDULED/RUNNING 任务即为阻塞，与单体 hasBlockingTasksForProduct 一致）。 */
    public static class BlockingTasksResponse implements Serializable {

        private static final long serialVersionUID = 1L;

        private boolean blocking;

        public BlockingTasksResponse() {
        }

        public BlockingTasksResponse(boolean blocking) {
            this.blocking = blocking;
        }

        public boolean isBlocking() {
            return blocking;
        }

        public void setBlocking(boolean blocking) {
            this.blocking = blocking;
        }
    }
}
