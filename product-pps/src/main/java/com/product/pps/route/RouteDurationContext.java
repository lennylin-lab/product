package com.product.pps.route;

import com.product.domain.entity.ProductMoldParam;

/**
 * 路线工序标准工时计算上下文。
 */
public record RouteDurationContext(
        long batchQty,
        Long productId,
        ProductMoldParam moldParam,
        String opCode,
        String stdTimeModel) {
}
