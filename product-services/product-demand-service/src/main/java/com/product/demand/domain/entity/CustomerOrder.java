package com.product.demand.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.product.demand.common.annotation.Excel;
import com.product.demand.common.core.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单对象 customer_order
 *
 * @author product
 * @date 2025-12-26
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("customer_order")
public class CustomerOrder extends BaseEntity {
    private static final long serialVersionUID = 1L;

    /** 订单ID（主键） */
    @Excel(name = "订单ID", readConverterExp = "主键")
    @TableId(value = "order_id", type =  IdType.ASSIGN_ID )
    private Long orderId;

    /** 客户ID */
    @Excel(name = "客户ID")
    @TableField(value = "customer_id")
    private Long customerId;

    /** 交期（排程常用 EDD） */
    @Excel(name = "交期")
    @TableField(value = "due_date")
    private LocalDateTime dueDate;

    /** 优先级（插单/加急） */
    @Excel(name = "优先级")
    @TableField(value = "priority")
    private Long priority;

    /** 订单状态：NEW/CONFIRMED/IN_PRODUCTION/DONE */
    @Excel(name = "订单状态", readConverterExp = "NEW=未确认,CONFIRMED=已确认,IN_PRODUCTION=生产中,DONE=已完成")
    @TableField(value = "status")
    private String status;

    /** 订单明细信息 */
    @TableField(exist = false)
    private List<OrderLine> orderLineList;

}
