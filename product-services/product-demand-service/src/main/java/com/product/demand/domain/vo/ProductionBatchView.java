package com.product.demand.domain.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 生产批次（订单行拆批）只读视图——需求域响应 DTO，非持久化模型。
 *
 * <p>字段与单体 <code>OrderLineMapper.selectProductionBatchList</code> 的查询列一致
 * （batch_id, order_line_id, batch_qty, status, planned_start, planned_end, create_time, update_time）。
 * production_batch 归 product-planning 所有（ADR-0005）；Phase 3 planning 未迁移，
 * 订单行详情中的该列表恒为空列表（与单体"无拆批批次"场景的响应一致）；
 * Phase 4 由 planning 契约填充本视图。</p>
 */
@Data
public class ProductionBatchView implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long batchId;

    private Long orderLineId;

    private Long batchQty;

    private String status;

    private LocalDateTime plannedStart;

    private LocalDateTime plannedEnd;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
