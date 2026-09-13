package com.product.pps.service.impl;

import com.product.common.exception.ServiceException;
import com.product.domain.entity.ProductMoldParam;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InjectDurationCalculatorTest {

    private final InjectDurationCalculator calculator = new InjectDurationCalculator();

    @Test
    void calculateDurationMinShouldUseA2Formula() {
        ProductMoldParam param = validParam();

        long duration = calculator.calculateDurationMin(1000, param, 100L);

        assertEquals(293L, duration);
    }

    @Test
    void calculateDurationMinShouldRejectMissingOrInvalidParam() {
        assertThrows(ServiceException.class, () -> calculator.calculateDurationMin(100, null, 1L));

        ProductMoldParam invalid = validParam();
        invalid.setCycleTimeSec(BigDecimal.ZERO);
        assertThrows(ServiceException.class, () -> calculator.calculateDurationMin(100, invalid, 1L));
    }

    private ProductMoldParam validParam() {
        ProductMoldParam param = new ProductMoldParam();
        param.setMoldId(201L);
        param.setCycleTimeSec(new BigDecimal("30"));
        param.setCavity(2);
        param.setYieldRate(new BigDecimal("0.95"));
        param.setUtilization(new BigDecimal("0.90"));
        return param;
    }
}
