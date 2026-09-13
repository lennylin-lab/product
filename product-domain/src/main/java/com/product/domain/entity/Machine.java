package com.product.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.product.common.annotation.Excel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 注塑机扩展信息对象 machine
 *
 * @author product
 * @date 2026-01-01
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode
@TableName("machine")
public class Machine {
    private static final long serialVersionUID = 1L;

    /** 注塑机ID */
    @TableId(value = "machine_id", type = IdType.ASSIGN_ID)
    private Long machineId;

    /** 锁模力 */
    @Excel(name = "锁模力")
    @TableField(value = "tonnage")
    private Integer tonnage;

    /** 默认换模基准时间（分钟） */
    @Excel(name = "默认换模基准时间", readConverterExp = "默认换模基准时间（分钟）")
    @TableField(value = "default_setup_time_min")
    private Integer defaultSetupTimeMin;

    /** 机模兼容矩阵 */
    @TableField(exist = false)
    private List<MachineMoldCompatibility> moldCompatibilityList;

}
