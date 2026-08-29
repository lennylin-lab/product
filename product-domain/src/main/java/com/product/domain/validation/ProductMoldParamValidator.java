package com.product.domain.validation;

import com.product.common.exception.ServiceException;
import com.product.common.utils.StringUtils;
import com.product.domain.entity.ProductMoldParam;
import org.apache.commons.collections4.CollectionUtils;

import java.math.BigDecimal;
import java.util.List;

/**
 * 产品模具参数（A2 产能）校验。
 */
public final class ProductMoldParamValidator {

    private ProductMoldParamValidator() {
    }

    /**
     * 创建/更新产品时校验：至少一条模具参数，且每条字段有效。
     */
    public static void requireMoldParamsForProduct(List<ProductMoldParam> moldParams, Long productId) {
        if (CollectionUtils.isEmpty(moldParams)) {
            throw new ServiceException("产品必须指定至少一条模具参数");
        }
        for (ProductMoldParam param : moldParams) {
            requireValidParam(param, productId);
        }
    }

    /**
     * 校验单条模具参数字段；失败时抛出 {@link ServiceException}。
     */
    public static void requireValidParam(ProductMoldParam param, Long productId) {
        if (param == null) {
            throw new ServiceException("产品模具参数不能为空: productId=" + productId);
        }
        if (StringUtils.isBlank(param.getMoldId())) {
            throw new ServiceException("产品模具参数缺少 moldId: productId=" + productId);
        }
        BigDecimal cycleTimeSec = param.getCycleTimeSec();
        if (cycleTimeSec == null || cycleTimeSec.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ServiceException("产品模具参数节拍无效: productId=" + productId
                    + ", moldId=" + param.getMoldId());
        }
        if (param.getCavity() == null || param.getCavity() <= 0) {
            throw new ServiceException("产品模具参数型腔数无效: productId=" + productId
                    + ", moldId=" + param.getMoldId());
        }
        validateOptionalRate(param.getYieldRate(), "良率", productId, param.getMoldId());
        validateOptionalRate(param.getUtilization(), "利用率", productId, param.getMoldId());
    }

    private static void validateOptionalRate(BigDecimal rate, String label, Long productId, String moldId) {
        if (rate == null) {
            return;
        }
        if (rate.compareTo(BigDecimal.ZERO) <= 0 || rate.compareTo(BigDecimal.ONE) > 0) {
            throw new ServiceException("产品模具参数" + label + "无效(须在(0,1]): productId=" + productId
                    + ", moldId=" + moldId);
        }
    }
}
