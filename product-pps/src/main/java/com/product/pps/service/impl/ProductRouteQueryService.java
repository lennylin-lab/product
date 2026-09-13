package com.product.pps.service.impl;

import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.product.domain.entity.ProductRoute;
import com.product.domain.entity.RouteOperation;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 产品工艺路线查询。
 */
@Component
public class ProductRouteQueryService {

    /**
     * 批量加载产品启用的路线工序（按 sequence 升序）。
     */
    public Map<Long, List<RouteOperation>> loadActiveRouteOperationsByProductIds(Collection<Long> productIds) {
        if (CollectionUtils.isEmpty(productIds)) {
            return Map.of();
        }
        Set<Long> normalizedProductIds = productIds.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (normalizedProductIds.isEmpty()) {
            return Map.of();
        }
        List<ProductRoute> routes = Db.lambdaQuery(ProductRoute.class)
                .in(ProductRoute::getProductId, normalizedProductIds)
                .eq(ProductRoute::getIsActive, 1)
                .list();
        if (CollectionUtils.isEmpty(routes)) {
            return Map.of();
        }
        Map<Long, ProductRoute> routeByProduct = new HashMap<>();
        for (ProductRoute route : routes) {
            if (route == null || route.getProductId() == null || route.getRouteId() == null) {
                continue;
            }
            routeByProduct.putIfAbsent(route.getProductId(), route);
        }
        if (routeByProduct.isEmpty()) {
            return Map.of();
        }
        List<Long> routeIds = routeByProduct.values().stream()
                .map(ProductRoute::getRouteId)
                .distinct()
                .toList();
        Map<Long, List<RouteOperation>> operationsByRoute = Db.lambdaQuery(RouteOperation.class)
                .in(RouteOperation::getRouteId, routeIds)
                .list()
                .stream()
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(RouteOperation::getRouteId));
        Map<Long, List<RouteOperation>> result = new HashMap<>();
        routeByProduct.forEach((productId, route) -> {
            List<RouteOperation> operations = operationsByRoute.getOrDefault(route.getRouteId(), List.of());
            if (CollectionUtils.isEmpty(operations)) {
                return;
            }
            List<RouteOperation> ordered = operations.stream()
                    .sorted(Comparator.comparing(RouteOperation::getSequence, Comparator.nullsLast(Integer::compareTo))
                            .thenComparing(RouteOperation::getOpCode, Comparator.nullsLast(String::compareTo)))
                    .toList();
            result.put(productId, ordered);
        });
        return result;
    }
}
