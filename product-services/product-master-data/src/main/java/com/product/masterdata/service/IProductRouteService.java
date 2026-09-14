package com.product.masterdata.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.product.masterdata.domain.entity.ProductRoute;

import java.util.List;

/**
 * 产品工艺路线Service接口（单体 product-pps IProductRouteService 移植，
 * 路线及其工序归 master_data_db 所有；ADR-0002）。
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
