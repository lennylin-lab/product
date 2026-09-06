package com.product.pps.route.model;

import com.product.common.constant.OperationTaskConstants;
import com.product.common.constant.RouteOperationConstants;
import com.product.pps.route.RouteDurationContext;
import org.springframework.stereotype.Component;

@Component
public class PostUnitTimeModel implements RouteStdTimeModel {

    @Override
    public String code() {
        return RouteOperationConstants.TM_POST_UNIT;
    }

    @Override
    public long calculateDurationMin(RouteDurationContext context) {
        return OperationTaskConstants.STD_DURATION_MIM.get(2) * context.batchQty();
    }
}
