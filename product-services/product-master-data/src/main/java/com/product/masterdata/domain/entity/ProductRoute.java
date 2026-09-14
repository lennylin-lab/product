package com.product.masterdata.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.product.masterdata.common.annotation.Excel;
import com.product.masterdata.common.core.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 产品工艺路线头对象 product_route
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("product_route")
public class ProductRoute extends BaseEntity {

    @TableId(value = "route_id", type = IdType.ASSIGN_ID)
    private Long routeId;

    /** 产品ID */
    @Excel(name = "产品ID")
    @TableField("product_id")
    private Long productId;

    /** 工艺/图纸版本 */
    @Excel(name = "版本")
    @TableField("version")
    private String version;

    /** 是否启用（1启用） */
    @TableField("is_active")
    private Integer isActive;

    /** 路线工序定义列表 */
    @TableField(exist = false)
    private List<RouteOperation> operations;
}
