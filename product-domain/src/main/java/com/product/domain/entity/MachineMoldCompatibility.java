package com.product.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 机模兼容矩阵对象 machine_mold_compatibility
 *
 * @author product
 * @date 2026-04-25
 */
@Data
@TableName("machine_mold_compatibility")
public class MachineMoldCompatibility {
    private static final long serialVersionUID = 1L;

    /** 注塑机ID */
    @TableField("machine_id")
    private String machineId;

    /** 模具ID */
    @TableField("mold_id")
    private String moldId;

    /** 是否兼容 */
    @TableField("is_compatible")
    private Integer isCompatible;
}
