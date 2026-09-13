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
 * 订单明细对象 order_line
 *
 * @author product
 * @date 2025-12-27
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode
@TableName("order_line")
public class OrderLine{
    private static final long serialVersionUID = 1L;

    /** 订单行ID（主键） */
    @TableId(value = "order_line_id", type =  IdType.ASSIGN_ID )
    private Long orderLineId;

    /** 所属订单ID（外键） */
    @Excel(name = "所属订单ID")
    @TableField(value = "order_id")
    private Long orderId;

    /** 产品/SKU */
    @Excel(name = "产品/SKU")
    @TableField(value = "product_id")
    private Long productId;

    /** 需求数量（件） */
    @Excel(name = "需求数量")
    @TableField(value = "qty")
    private Long qty;

    @Excel(name = "已拆批数量")
    @TableField(value = "allocated_qty")
    private Long allocatedQty;

    /** 订单行状态 */
    @Excel(name = "订单行状态", readConverterExp = "NEW=未释放,RELEASE=已释放,IN_PRODUCTION=生产中,DONE=已完成")
    @TableField(value = "status")
    private String status;

    /** 生产批次（订单行拆批）信息 */
    @TableField(exist = false)
    private List<ProductionBatch> productionBatchList;

}
