package com.product.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.product.common.annotation.Excel;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 产品模具参数对象 product_mold_param
 *
 * @author product
 * @date 2026-04-25
 */
@Data
@TableName("product_mold_param")
public class ProductMoldParam {
    private static final long serialVersionUID = 1L;

    /** 产品ID */
    @Excel(name = "产品ID")
    @TableField("product_id")
    private Long productId;

    /** 模具ID */
    @Excel(name = "模具ID")
    @TableField("mold_id")
    private String moldId;

    /** 节拍（秒/模次），即注塑机完成一个注塑循环所需的时间 */
    @TableField("cycle_time_sec")
    private BigDecimal cycleTimeSec;

    /** 型腔数，该模具在一次循环中同时产出的产品数量 */
    @TableField("cavity")
    private Integer cavity;

    /** 良率（0~1），用于计算实际需要的生产数量 */
    @TableField("yield_rate")
    private BigDecimal yieldRate;

    /** 有效利用率（0~1），扣除停机、换模等损耗后的实际产能系数 */
    @TableField("utilization")
    private BigDecimal utilization;
}
