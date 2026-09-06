package com.product.pps.route.model;

import com.product.common.constant.OperationTaskConstants;
import com.product.common.constant.RouteOperationConstants;
import com.product.pps.route.RouteDurationContext;
import org.springframework.stereotype.Component;

@Component
public class SetupBaseTimeModel implements RouteStdTimeModel {

    @Override
    public String code() {
        return RouteOperationConstants.TM_SETUP_BASE;
    }

    @Override
    public long calculateDurationMin(RouteDurationContext context) {
        return OperationTaskConstants.STD_DURATION_MIM.get(0) * context.batchQty();
    }
}
