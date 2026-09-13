package com.product.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.product.common.annotation.Excel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import com.product.common.core.entity.BaseEntity;

/**
 * 客户对象 customer
 *
 * @author product
 * @date 2025-12-23
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("customer")
public class Customer extends BaseEntity {
    private static final long serialVersionUID = 1L;

    /** 客户id */
    @TableId(value = "customer_id", type = IdType.ASSIGN_ID )
    private Long customerId;

    /** 客户名 */
    @Excel(name = "客户名")
    @TableField(value = "customer_name")
    private String customerName;

    /** 备注 */
    @Excel(name = "备注")
    @TableField(value = "remark")
    private String remark;

}
