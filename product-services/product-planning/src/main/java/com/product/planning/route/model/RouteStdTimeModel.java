package com.product.planning.route.model;

import com.product.planning.route.RouteDurationContext;

/**
 * 路线工序标准工时模型。
 */
public interface RouteStdTimeModel {

    String code();

    long calculateDurationMin(RouteDurationContext context);
}
