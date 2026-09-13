package com.product.domain.validation;

import com.product.common.exception.ServiceException;
import com.product.domain.entity.ProductMoldParam;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductMoldParamValidatorTest {

    @Test
    void requireMoldParamsForProductShouldRejectEmptyList() {
        assertThrows(ServiceException.class,
                () -> ProductMoldParamValidator.requireMoldParamsForProduct(List.of(), 1L));
    }

    @Test
    void requireValidParamShouldAcceptCompleteParam() {
        ProductMoldParamValidator.requireValidParam(validParam(), 100L);
    }

    @Test
    void requireValidParamShouldRejectInvalidFields() {
        assertThrows(ServiceException.class, () -> ProductMoldParamValidator.requireValidParam(null, 1L));

        ProductMoldParam missingMoldId = validParam();
        missingMoldId.setMoldId(null);
        assertThrows(ServiceException.class, () -> ProductMoldParamValidator.requireValidParam(missingMoldId, 1L));

        ProductMoldParam invalidCycle = validParam();
        invalidCycle.setCycleTimeSec(BigDecimal.ZERO);
        assertThrows(ServiceException.class, () -> ProductMoldParamValidator.requireValidParam(invalidCycle, 1L));
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
