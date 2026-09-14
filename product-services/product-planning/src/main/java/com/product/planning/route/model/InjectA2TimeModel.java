package com.product.planning.route.model;

import com.product.planning.common.constant.RouteOperationConstants;
import com.product.planning.route.RouteDurationContext;
import com.product.planning.service.impl.InjectDurationCalculator;
import org.springframework.stereotype.Component;

@Component
public class InjectA2TimeModel implements RouteStdTimeModel {

    private final InjectDurationCalculator injectDurationCalculator;

    public InjectA2TimeModel(InjectDurationCalculator injectDurationCalculator) {
        this.injectDurationCalculator = injectDurationCalculator;
    }

    @Override
    public String code() {
        return RouteOperationConstants.TM_INJECT_A2;
    }

    @Override
    public long calculateDurationMin(RouteDurationContext context) {
        return injectDurationCalculator.calculateDurationMin(
                context.batchQty(), context.moldParam(), context.productId());
    }
}
