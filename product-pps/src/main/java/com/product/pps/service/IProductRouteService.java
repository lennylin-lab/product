package com.product.pps.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.product.domain.entity.ProductRoute;

import java.util.List;

/**
 * 产品工艺路线 Service 接口。
 */
public interface IProductRouteService {

    ProductRoute getByRouteId(Long routeId);

    ProductRoute getActiveByProductId(Long productId);

    List<ProductRoute> listByProductId(Long productId);

    Page<ProductRoute> selectProductRoutePage(Page<ProductRoute> page, ProductRoute query);

    Long createRoute(ProductRoute route);

    void updateRoute(ProductRoute route);

    void activateRoute(Long routeId);

    void deleteRoute(Long routeId);

    void saveActiveRouteForProduct(Long productId, ProductRoute route);
}
