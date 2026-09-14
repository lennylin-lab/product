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

/**
 * 派工资源占用明细对象 task_assignment_resource
 *
 * @author product
 * @date 2026-04-28
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("task_assignment_resource")
public class TaskAssignmentResource extends BaseEntity {
    private static final long serialVersionUID = 1L;

    /** 明细主键 */
    @TableId(value = "assignment_resource_id", type = IdType.ASSIGN_ID)
    private Long assignmentResourceId;

    /** 派工记录ID */
    @TableField("assignment_id")
    private Long assignmentId;

    /** 任务ID */
    @Excel(name = "任务ID")
    @TableField("task_id")
    private Long taskId;

    /** 资源ID */
    @Excel(name = "资源ID")
    @TableField("resource_id")
    private Long resourceId;

    /** 资源类型 */
    @Excel(name = "资源类型")
    @TableField("resource_type")
    private String resourceType;

    /** 资源角色 */
    @TableField("resource_role")
    private String resourceRole;

    /** 关联需求ID */
    @TableField("requirement_id")
    private Long requirementId;

    /** 计划开始时间 */
    @TableField("planned_start")
    private LocalDateTime plannedStart;

    /** 计划结束时间 */
    @TableField("planned_end")
    private LocalDateTime plannedEnd;

    /** 资源上的顺序号 */
    @TableField("sequence_on_resource")
    private Long sequenceOnResource;
}
