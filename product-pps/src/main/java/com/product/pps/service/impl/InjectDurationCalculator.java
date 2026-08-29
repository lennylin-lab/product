package com.product.pps.service.impl;

import com.product.common.exception.ServiceException;
import com.product.domain.entity.ProductMoldParam;
import com.product.domain.validation.ProductMoldParamValidator;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * INJECT 工序标准时长计算器（A2 产能公式）。
 */
@Component
public class InjectDurationCalculator {

    public long calculateDurationMin(long batchQty, ProductMoldParam param, Long productId) {
        ProductMoldParamValidator.requireValidParam(param, productId);
        if (batchQty <= 0) {
            throw new ServiceException("批次数量无效，无法计算 INJECT 时长: batchQty=" + batchQty);
        }

        BigDecimal cycleTimeSec = param.getCycleTimeSec();
        int cavity = param.getCavity();
        BigDecimal yieldRate = defaultRate(param.getYieldRate());
        BigDecimal utilization = defaultRate(param.getUtilization());

        BigDecimal cyclesPerHour = BigDecimal.valueOf(3600)
                .divide(cycleTimeSec, 10, RoundingMode.HALF_UP);
        BigDecimal actualOutputPerHour = cyclesPerHour
                .multiply(BigDecimal.valueOf(cavity))
                .multiply(yieldRate)
                .multiply(utilization);
        if (actualOutputPerHour.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ServiceException("产品模具参数无法产出有效产能: productId=" + productId
                    + ", moldId=" + param.getMoldId());
        }

        BigDecimal runTimeHours = BigDecimal.valueOf(batchQty)
                .divide(actualOutputPerHour, 10, RoundingMode.CEILING);
        return runTimeHours.multiply(BigDecimal.valueOf(60))
                .setScale(0, RoundingMode.CEILING)
                .longValue();
    }

    private BigDecimal defaultRate(BigDecimal rate) {
        return rate == null || rate.compareTo(BigDecimal.ZERO) <= 0 ? BigDecimal.ONE : rate;
    }
}
