package com.product.pps.route.model;

import com.product.pps.route.RouteDurationContext;

/**
 * 路线工序标准工时模型。
 */
public interface RouteStdTimeModel {

    String code();

    long calculateDurationMin(RouteDurationContext context);
}
