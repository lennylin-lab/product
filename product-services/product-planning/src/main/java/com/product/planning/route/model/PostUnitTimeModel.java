package com.product.planning.route.model;

import com.product.planning.common.constant.OperationTaskConstants;
import com.product.planning.common.constant.RouteOperationConstants;
import com.product.planning.route.RouteDurationContext;
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
