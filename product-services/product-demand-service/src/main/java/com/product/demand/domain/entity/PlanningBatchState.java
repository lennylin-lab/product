package com.product.demand.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * planning 批次状态本域投影（Phase 5，事件携带状态迁移的事件溯源投影；服务权属表，
 * 非单体基线表）。
 *
 * <p>依据：ADR-0005 禁止跨服务读写他方表，design.md §4/ADR-0004 禁止消费链路内同步
 * 调用他域——而单体 OrderLineStatusRefresher/CustomerOrderStatusRefresher 需要"某订单行
 * 下全部批次状态"做聚合。事件链中该输入由 batch.progress.changed 事件携带，本服务消费时
 * 落为本域投影表，聚合查询本地完成（与单体同构：select status from production_batch
 * where order_line_id = X 的语义等价物）。投影为可重建派生态（重放事件/对账修复即可
 * 重建），不作为跨服务事实源；删除订单行/订单时随行清理（消费不到的批次不再参与聚合，
 * 与单体删除批次后聚合范围一致）。</p>
 */
@Data
@NoArgsConstructor
@TableName("planning_batch_state")
public class PlanningBatchState {

    /** 批次ID（planning production_batch.batch_id 的投影主键）。 */
    @TableId(value = "batch_id", type = IdType.INPUT)
    private Long batchId;

    /** 订单行ID。 */
    @TableField("order_line_id")
    private Long orderLineId;

    /** 批次状态（PLANNED/RELEASED/IN_PROCESS/DONE）。 */
    @TableField("status")
    private String status;

    /** 承载本状态的最后一个事件 ID（追溯）。 */
    @TableField("last_event_id")
    private String lastEventId;

    /** 事件发生时间（单调性守卫已通过后写入）。 */
    @TableField("occurred_at")
    private LocalDateTime occurredAt;

    /** 投影更新时间。 */
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
