package com.product.planning.domain.vo;

import com.product.planning.common.annotation.Excel;
import com.product.planning.domain.entity.ProductionBatch;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * @Auther: chuan
 * @Date: 2025/12/28 - 12 - 28 - 17:27
 * @Description: com.product.planning.domain.vo
 * @version: 1.0
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ProductionBatchVO extends ProductionBatch {
    @Excel(name = "订单id")
    private Long orderId;

    @Excel(name = "交期")
    private LocalDateTime dueDate;

    @Excel(name = "产品名")
    private String productName;
}
