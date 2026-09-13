package com.product.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.product.common.annotation.Excel;
import com.product.common.core.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 派工/排程结果对象 task_assignment
 *
 * @author product
 * @date 2026-01-02
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("task_assignment")
public class TaskAssignment  extends BaseEntity {
    private static final long serialVersionUID = 1L;

    /** 派工记录ID */
    @TableId(value = "assignment_id", type = IdType.ASSIGN_ID)
    private Long assignmentId;

    /** 任务ID */
    @Excel(name = "任务ID")
    @TableField(value = "task_id")
    private Long taskId;

    /** 注塑机ID */
    @Excel(name = "注塑机ID")
    @TableField(value = "machine_id")
    private Long machineId;

    /** 计划开始时间 */
    @Excel(name = "计划开始时间")
    @TableField(value = "planned_start")
    private LocalDateTime plannedStart;

    /** 计划结束时间 */
    @Excel(name = "计划结束时间")
    @TableField(value = "planned_end")
    private LocalDateTime plannedEnd;

    /** 资源上的顺序号（用于甘特图） */
    @Excel(name = "资源上的顺序号", readConverterExp = "资源上的顺序号（用于甘特图）")
    @TableField(value = "sequence_on_resource")
    private Long sequenceOnResource;

    /** 模具ID */
    @Excel(name = "模具ID")
    @TableField(exist = false)
    private Long moldId;

    /** 人员ID */
    @Excel(name = "人员ID")
    @TableField(exist = false)
    private Long personId;

    /** 工位ID */
    @Excel(name = "工位ID")
    @TableField(exist = false)
    private Long workstationId;

    /** 换型来源任务ID */
    @TableField(exist = false)
    private Long changeoverSourceTaskId;

    /** 换型时间（分钟） */
    @TableField(exist = false)
    private Integer changeoverTimeMin;

    /** 派工关联的资源需求快照 */
    @TableField(exist = false)
    private List<TaskResourceRequirement> resourceRequirementList;

    /** 派工关联的资源占用明细 */
    @TableField(exist = false)
    private List<TaskAssignmentResource> assignedResourceList;

    /** 各资源类型上的序号（resourceType -> sequenceOnResource） */
    @TableField(exist = false)
    private Map<String, Long> resourceSequenceMap;

    /** 批次ID（关联任务） */
    @Excel(name = "批次ID")
    @TableField(exist = false)
    private Long batchId;

    /** 工序（关联任务） */
    @Excel(name = "工序")
    @TableField(exist = false)
    private String opCode;

}
