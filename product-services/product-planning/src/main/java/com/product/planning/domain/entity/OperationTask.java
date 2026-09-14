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

import java.time.LocalDateTime;
import java.util.List;

/**
 * 工序任务对象 operation_task
 *
 * @author product
 * @date 2025-12-31
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("operation_task")
public class OperationTask  extends BaseEntity {
    private static final long serialVersionUID = 1L;

    /** 任务ID（主键） */
    @Excel(name = "任务ID", readConverterExp = "任务ID（主键）")
    @TableId(value = "task_id", type = IdType.ASSIGN_ID )
    private Long taskId;

    /** 所属批次ID（外键） */
    @Excel(name = "所属批次ID", readConverterExp = "所属批次ID（外键）")
    @TableField(value = "batch_id")
    private Long batchId;

    /** 工序（SETUP：换模调机 INJECT：注塑成型 POST_QC_PUTAWAY：后处理&检验&入库） */
    @Excel(name = "工序", readConverterExp = "SETUP=换模调机,INJECT=注塑成型,POST_QC_PUTAWAY=后处理&检验&入库")
    @TableField(value = "op_code")
    private String opCode;

    /** 工序顺序（1/2/3） */
    @Excel(name = "工序顺序")
    @TableField(value = "sequence")
    private Long sequence;

    /** 预计时长（分钟，A2/单位工时/固定+变动计算后写入） */
    @Excel(name = "预计时长")
    @TableField(value = "std_duration_min")
    private Long stdDurationMin;

    /** 最早可开始时间（备料备模/前置任务影响） */
    @Excel(name = "最早可开始时间")
    @TableField(value = "earliest_start")
    private LocalDateTime earliestStart;

    /** 任务状态（READY：带排程 SCHEDULED：已排程 RUNNING：执行中 PAUSED：挂起 DONE：已完成 CANCELLED：已取消） */
    @Excel(name = "任务状态", readConverterExp = "READY=待排程,SCHEDULED=已排程,RUNNING=执行中,PAUSED=挂起,DONE=已完成,CANCELLED=已取消")
    @TableField(value = "status")
    private String status;

    /** 工序排队策略（FIFO/SAME_MOLD_FIRST/EDD） */
    @TableField(value = "queue_policy")
    private String queuePolicy;

    /** 可用资源规则引用（排程换型判定） */
    @TableField(value = "eligible_resource_rule")
    private String eligibleResourceRule;

    /** 产品 ID（排程能力校验用，不落库） */
    @TableField(exist = false)
    private Long productId;

    /** 资源需求明细（机台/模具/人员/工位） */
    @TableField(exist = false)
    private List<TaskResourceRequirement> resourceRequirementList;

    /** 本次换型参考的前序任务ID */
    @TableField(exist = false)
    private Long changeoverSourceTaskId;

    /** 本次任务推导出的换型时长（分钟） */
    @TableField(exist = false)
    private Integer changeoverTimeMin;

    @TableField(exist = false)
    private List<String> statusList;
}
