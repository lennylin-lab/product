package com.product.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.product.common.annotation.BizIdPrefix;
import com.product.common.annotation.Excel;
import com.product.common.core.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 任务资源需求对象 task_resource_requirement
 *
 * @author product
 * @date 2026-04-25
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("task_resource_requirement")
@BizIdPrefix("TR")
public class TaskResourceRequirement extends BaseEntity {
    private static final long serialVersionUID = 1L;

    /** 需求ID */
    @TableId(value = "requirement_id", type = IdType.ASSIGN_UUID)
    private String requirementId;

    /** 任务ID */
    @Excel(name = "任务ID")
    @TableField("task_id")
    private String taskId;

    /** 资源类型 */
    @Excel(name = "资源类型", readConverterExp = "MACHINE=注塑机,MOLD=模具,PERSON=人员,WORKSTATION=工位")
    @TableField("resource_type")
    private String resourceType;

    /** 资源角色 */
    @TableField("resource_role")
    private String resourceRole;

    /** 指定资源ID */
    @TableField("resource_id")
    private String resourceId;

    /** 能力编码 */
    @TableField("capability_code")
    private String capabilityCode;

    /** 需求数量 */
    @TableField("required_count")
    private Integer requiredCount;

    /** 是否必需 */
    @TableField("is_mandatory")
    private Integer isMandatory;

    /** 换型来源资源ID */
    @TableField("changeover_source_resource_id")
    private String changeoverSourceResourceId;

    /** 换型时长（分钟） */
    @TableField("changeover_time_min")
    private Integer changeoverTimeMin;
}
