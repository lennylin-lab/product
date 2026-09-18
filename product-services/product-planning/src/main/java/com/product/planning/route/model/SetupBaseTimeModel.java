package com.product.planning.route.model;

import com.product.planning.common.constant.OperationTaskConstants;
import com.product.planning.common.constant.RouteOperationConstants;
import com.product.planning.route.RouteDurationContext;
import org.springframework.stereotype.Component;

/**
 * SETUP 工序标准工时：每次换型基准时长（占位常量，不随批量放大）。
 * 机台间实际换型差值由排程期 ChangeoverCalculator 独立叠加；机台级
 * default_setup_time_min 在 generateTask 时不可得（机台尚未分配）。
 */
@Component
public class SetupBaseTimeModel implements RouteStdTimeModel {

    @Override
    public String code() {
        return RouteOperationConstants.TM_SETUP_BASE;
    }

    @Override
    public long calculateDurationMin(RouteDurationContext context) {
        return OperationTaskConstants.STD_DURATION_MIM.get(0);
    }
}
