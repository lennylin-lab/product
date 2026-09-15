package com.product.masterdata.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.product.masterdata.common.annotation.Excel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import com.product.masterdata.common.core.entity.BaseEntity;

import java.util.List;

/**
 * 统一资源主表对象 resource
 *
 * @author product
 * @date 2026-01-01
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("resource")
public class Resource  extends BaseEntity {
    private static final long serialVersionUID = 1L;

    /** 资源ID（主键） */
    @TableId(value = "resource_id", type = IdType.ASSIGN_ID )
    private Long resourceId;

    /** 资源类型（MACHINE：注塑机 MOLD：模具 PERSON：人员 WORKSTATION：工位 FIXTURE：夹具） */
    @Excel(name = "资源类型", readConverterExp = "MACHINE=注塑机,MOLD=模具,PERSON=人员,WORKSTATION=工位,FIXTURE=夹具")
    @TableField(value = "resource_type")
    private String resourceType;

    /** 资源名称 */
    @Excel(name = "资源名称")
    @TableField(value = "name")
    private String name;

    /** 资源状态（AVAILABLE：可用 BUSY：忙碌 DOWN：故障 MAINTENANCE：保养中 OFFSHIFT：离班） */
    @Excel(name = "资源状态", readConverterExp = "AVAILABLE=可用,BUSY=忙碌,DOWN=故障,MAINTENANCE=保养中,OFFSHIFT=离班")
    @TableField(value = "status")
    private String status;

    /** 班次日历ID（外键） */
    @Excel(name = "班次日历ID")
    @TableField(value = "calendar_id")
    private Long calendarId;

    /** 车间/班组/部门 */
    @Excel(name = "车间/班组/部门")
    @TableField(value = "org_unit")
    private String orgUnit;

    /** 资源能力矩阵 */
    @TableField(exist = false)
    private List<ResourceCapability> capabilityList;

    /** 模具扩展信息 */
    @TableField(exist = false)
    private Mold mold;

    /** 注塑机扩展信息 */
    @TableField(exist = false)
    private Machine machine;

    /** 夹具扩展信息 */
    @TableField(exist = false)
    private Fixture fixture;

}
