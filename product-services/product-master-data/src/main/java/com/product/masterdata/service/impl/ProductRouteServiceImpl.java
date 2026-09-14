package com.product.masterdata.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.product.masterdata.common.exception.ServiceException;
import com.product.masterdata.common.utils.StringUtils;
import com.product.masterdata.domain.entity.Product;
import com.product.masterdata.domain.entity.ProductRoute;
import com.product.masterdata.domain.entity.RouteOperation;
import com.product.masterdata.mapper.ProductMapper;
import com.product.masterdata.route.RouteOperationValidator;
import com.product.masterdata.service.IProductRouteService;
import com.product.masterdata.service.MasterDataVersionService;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 产品工艺路线Service（单体 product-pps ProductRouteServiceImpl 移植，包路径服务内化）。
 *
 * <p>与单体的差异（均为跨域数据所有权重排，非行为变更，ADR-0005）：</p>
 * <ul>
 *   <li>产品存在性校验改为本库 product 表查询（原单体同库查询，语义不变）；</li>
 *   <li>{@link #hasBlockingTasksForProduct(Long)}（Phase 4 已接线）：经
 *       {@link RouteDeleteGuard} 还原单体语义——demand 契约 listOrderLineIdsByProduct +
 *       planning 契约 hasBlockingTasks(orderLineIds)；远程依赖不可用时 fail-closed
 *       （拒绝删除启用中路线，显式错误文案，见 RouteDeleteGuard javadoc）。</li>
 *   <li>所有写路径同事务递增主数据版本计数（MasterDataVersionService），契约快照版本来源。</li>
 * </ul>
 */
@Service
public class ProductRouteServiceImpl implements IProductRouteService {

    @Autowired
    private RouteOperationValidator routeOperationValidator;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private MasterDataVersionService versionService;

    @Autowired
    private RouteDeleteGuard routeDeleteGuard;

    @Override
    public ProductRoute getByRouteId(Long routeId) {
        if (routeId == null) {
            return null;
        }
        ProductRoute route = Db.lambdaQuery(ProductRoute.class)
                .eq(ProductRoute::getRouteId, routeId)
                .one();
        if (route == null) {
            return null;
        }
        route.setOperations(loadOperations(routeId));
        return route;
    }

    @Override
    public ProductRoute getActiveByProductId(Long productId) {
        if (productId == null) {
            return null;
        }
        ProductRoute route = Db.lambdaQuery(ProductRoute.class)
                .eq(ProductRoute::getProductId, productId)
                .eq(ProductRoute::getIsActive, 1)
                .last("limit 1")
                .one();
        if (route == null) {
            return null;
        }
        route.setOperations(loadOperations(route.getRouteId()));
        return route;
    }

    @Override
    public List<ProductRoute> listByProductId(Long productId) {
        if (productId == null) {
            return List.of();
        }
        List<ProductRoute> routes = Db.lambdaQuery(ProductRoute.class)
                .eq(ProductRoute::getProductId, productId)
                .orderByDesc(ProductRoute::getIsActive)
                .list();
        for (ProductRoute route : routes) {
            if (route != null && route.getRouteId() != null) {
                route.setOperations(loadOperations(route.getRouteId()));
            }
        }
        return routes;
    }

    @Override
    public Page<ProductRoute> selectProductRoutePage(Page<ProductRoute> page, ProductRoute query) {
        Page<ProductRoute> result = Db.lambdaQuery(ProductRoute.class)
                .eq(query != null && query.getProductId() != null, ProductRoute::getProductId,
                        query == null ? null : query.getProductId())
                .eq(query != null && query.getIsActive() != null, ProductRoute::getIsActive,
                        query == null ? null : query.getIsActive())
                .eq(query != null && StringUtils.isNotEmpty(query.getVersion()), ProductRoute::getVersion,
                        query == null ? null : query.getVersion())
                .page(page);
        if (result != null && CollectionUtils.isNotEmpty(result.getRecords())) {
            for (ProductRoute route : result.getRecords()) {
                if (route != null && route.getRouteId() != null) {
                    route.setOperations(loadOperations(route.getRouteId()));
                }
            }
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createRoute(ProductRoute route) {
        if (route == null) {
            throw new ServiceException("工艺路线不能为空");
        }
        validateProductExists(route.getProductId());
        routeOperationValidator.validate(route.getOperations());
        normalizeRoute(route);
        if (!Db.save(route)) {
            throw new ServiceException("创建工艺路线失败");
        }
        saveOperations(route.getRouteId(), route.getOperations());
        if (Integer.valueOf(1).equals(route.getIsActive())) {
            deactivateOtherRoutes(route.getProductId(), route.getRouteId());
        }
        versionService.bump();
        return route.getRouteId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateRoute(ProductRoute route) {
        if (route == null || route.getRouteId() == null) {
            throw new ServiceException("路线ID不能为空");
        }
        ProductRoute existing = Db.lambdaQuery(ProductRoute.class)
                .eq(ProductRoute::getRouteId, route.getRouteId())
                .one();
        if (existing == null) {
            throw new ServiceException("工艺路线不存在: " + route.getRouteId());
        }
        if (route.getProductId() != null && !Objects.equals(route.getProductId(), existing.getProductId())) {
            throw new ServiceException("不允许修改路线所属产品");
        }
        route.setProductId(existing.getProductId());
        routeOperationValidator.validate(route.getOperations());
        if (route.getIsActive() == null) {
            route.setIsActive(existing.getIsActive());
        }
        normalizeRoute(route);
        if (!Db.updateById(route)) {
            throw new ServiceException("更新工艺路线失败");
        }
        replaceOperations(route.getRouteId(), route.getOperations());
        if (Integer.valueOf(1).equals(route.getIsActive())) {
            deactivateOtherRoutes(route.getProductId(), route.getRouteId());
        }
        versionService.bump();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void activateRoute(Long routeId) {
        ProductRoute route = requireRoute(routeId);
        if (Integer.valueOf(1).equals(route.getIsActive())) {
            return;
        }
        routeOperationValidator.validate(loadOperations(routeId));
        deactivateOtherRoutes(route.getProductId(), routeId);
        boolean updated = Db.lambdaUpdate(ProductRoute.class)
                .set(ProductRoute::getIsActive, 1)
                .eq(ProductRoute::getRouteId, routeId)
                .update();
        if (!updated) {
            throw new ServiceException("启用工艺路线失败: " + routeId);
        }
        versionService.bump();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteRoute(Long routeId) {
        ProductRoute route = requireRoute(routeId);
        if (Integer.valueOf(1).equals(route.getIsActive()) && hasBlockingTasksForProduct(route.getProductId())) {
            throw new ServiceException("启用中的工艺路线存在进行中的工序任务，无法删除");
        }
        Db.lambdaUpdate(RouteOperation.class)
                .eq(RouteOperation::getRouteId, routeId)
                .remove();
        if (!Db.lambdaUpdate(ProductRoute.class)
                .eq(ProductRoute::getRouteId, routeId)
                .remove()) {
            throw new ServiceException("删除工艺路线失败: " + routeId);
        }
        versionService.bump();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveActiveRouteForProduct(Long productId, ProductRoute route) {
        if (productId == null) {
            throw new ServiceException("产品ID不能为空");
        }
        if (route == null) {
            return;
        }
        route.setProductId(productId);
        route.setIsActive(1);
        if (route.getRouteId() == null) {
            createRoute(route);
            return;
        }
        ProductRoute existing = Db.lambdaQuery(ProductRoute.class)
                .eq(ProductRoute::getRouteId, route.getRouteId())
                .one();
        if (existing == null) {
            createRoute(route);
            return;
        }
        updateRoute(route);
        activateRoute(route.getRouteId());
    }

    private ProductRoute requireRoute(Long routeId) {
        ProductRoute route = Db.lambdaQuery(ProductRoute.class)
                .eq(ProductRoute::getRouteId, routeId)
                .one();
        if (route == null) {
            throw new ServiceException("工艺路线不存在: " + routeId);
        }
        return route;
    }

    private void validateProductExists(Long productId) {
        if (productId == null) {
            throw new ServiceException("产品ID不能为空");
        }
        Product product = productMapper.selectById(productId);
        if (product == null) {
            throw new ServiceException("产品不存在: productId=" + productId);
        }
    }

    private void normalizeRoute(ProductRoute route) {
        if (StringUtils.isEmpty(route.getVersion())) {
            route.setVersion("v1");
        }
        if (route.getIsActive() == null) {
            route.setIsActive(0);
        }
    }

    private List<RouteOperation> loadOperations(Long routeId) {
        return Db.lambdaQuery(RouteOperation.class)
                .eq(RouteOperation::getRouteId, routeId)
                .list()
                .stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(RouteOperation::getSequence, Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(RouteOperation::getOpCode, Comparator.nullsLast(String::compareTo)))
                .toList();
    }

    private void saveOperations(Long routeId, List<RouteOperation> operations) {
        if (CollectionUtils.isEmpty(operations)) {
            return;
        }
        for (RouteOperation operation : operations) {
            operation.setRouteId(routeId);
            operation.setOpId(null);
        }
        if (!Db.saveBatch(operations)) {
            throw new ServiceException("保存路线工序失败: routeId=" + routeId);
        }
    }

    private void replaceOperations(Long routeId, List<RouteOperation> operations) {
        Db.lambdaUpdate(RouteOperation.class)
                .eq(RouteOperation::getRouteId, routeId)
                .remove();
        saveOperations(routeId, operations);
    }

    private void deactivateOtherRoutes(Long productId, Long activeRouteId) {
        Db.lambdaUpdate(ProductRoute.class)
                .set(ProductRoute::getIsActive, 0)
                .eq(ProductRoute::getProductId, productId)
                .ne(ProductRoute::getRouteId, activeRouteId)
                .update();
    }

    /**
     * Phase 4 接线：跨服务还原单体"启用中路线存在进行中工序任务则禁止删除"语义
     * （demand 契约按产品取订单行 + planning 契约阻塞任务判断，见 RouteDeleteGuard）。
     */
    private boolean hasBlockingTasksForProduct(Long productId) {
        return routeDeleteGuard.hasBlockingTasksForProduct(productId);
    }
}
