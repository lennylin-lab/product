package com.product.planning.route.model;

import com.product.planning.common.constant.OperationTaskConstants;
import com.product.planning.common.constant.RouteOperationConstants;
import com.product.planning.route.RouteDurationContext;
import org.springframework.stereotype.Component;

/**
 * POST_QC_PUTAWAY 工序标准工时：按批次活动计（占位基准，不随批量放大）。
 * 模型码 TM_POST_UNIT 已落库故类名/码不变；真实单位工时数据接入后再启用
 * 单位语义，届时需配班次上限校验/分批（issue #12 后续项）。
 */
@Component
public class PostUnitTimeModel implements RouteStdTimeModel {

    @Override
    public String code() {
        return RouteOperationConstants.TM_POST_UNIT;
    }

    @Override
    public long calculateDurationMin(RouteDurationContext context) {
        return OperationTaskConstants.STD_DURATION_MIM.get(2);
    }
}
