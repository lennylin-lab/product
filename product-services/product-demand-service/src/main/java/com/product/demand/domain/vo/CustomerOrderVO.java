package com.product.demand.domain.vo;

import com.product.demand.domain.entity.CustomerOrder;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * @Auther: chuan
 * @Date: 2025/12/25 - 12 - 25 - 22:41
 * @Description: com.product.demand.domain.vo
 * @version: 1.0
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class CustomerOrderVO extends CustomerOrder {
    private String customerName;
}
