package com.product.planning.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.product.planning.common.annotation.Excel;
import com.product.planning.common.core.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;


/**
 * 生产批次（订单行拆批）对象 production_batch
 *
 * @author product
 * @date 2025-12-27
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("production_batch")
public class ProductionBatch extends BaseEntity {
    private static final long serialVersionUID = 1L;

    /** 批次ID（主键） */
    @TableId(value = "batch_id", type = IdType.ASSIGN_ID)
    private Long batchId;

    /** 来源订单行ID（外键） */
    @Excel(name = "来源订单行ID")
    @TableField(value = "order_line_id")
    private Long orderLineId;

    /** 批次数量（件） */
    @Excel(name = "批次数量")
    @TableField(value = "batch_qty")
    private Long batchQty;

    /** 批次状态：PLANNED/RELEASED/IN_PROCESS/DONE */
    @Excel(name = "批次状态", readConverterExp = "PLANNED=计划中,RELEASED=已释放,IN_PROCESS=执行中,DONE=完成")
    @TableField(value = "status")
    private String status;

    /** 计划开工 */
    @DateTimeFormat(pattern = "yyyy-MM-dd[' 'HH:mm:ss']")
    @Excel(name = "计划开工", width = 30, dateFormat = "yyyy-MM-dd HH:mm:ss")
    @TableField(value = "planned_start")
    private LocalDateTime plannedStart;

    /** 计划完工 */
    @DateTimeFormat(pattern = "yyyy-MM-dd[' 'HH:mm:ss']")
    @Excel(name = "计划完工", width = 30, dateFormat = "yyyy-MM-dd HH:mm:ss")
    @TableField(value = "planned_end")
    private LocalDateTime plannedEnd;

}
