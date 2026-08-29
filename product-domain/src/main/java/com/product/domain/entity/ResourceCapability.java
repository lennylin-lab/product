package com.product.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.product.common.annotation.Excel;
import lombok.Data;

/**
 * 资源能力矩阵对象 resource_capability
 *
 * @author product
 * @date 2026-04-25
 */
@Data
@TableName("resource_capability")
public class ResourceCapability {
    private static final long serialVersionUID = 1L;

    /** 资源ID */
    @TableField("resource_id")
    private String resourceId;

    /** 工序编码 */
    @Excel(name = "工序编码")
    @TableField("op_code")
    private String opCode;

    /** 产品ID */
    @Excel(name = "产品ID")
    @TableField("product_id")
    private Long productId;

    /** 是否启用（1=启用，0=禁用） */
    @TableField("is_enabled")
    private Integer isEnabled;

    /** 优先权重，数值越大优先级越高，排程时同类型资源中选择权重最高的 */
    @TableField("priority_weight")
    private Integer priorityWeight;
}
