package com.product.pps.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.product.common.constant.StatusConstants;
import com.product.common.exception.ServiceException;
import com.product.common.utils.StringUtils;
import com.product.domain.entity.OperationTask;
import com.product.domain.entity.OrderLine;
import com.product.domain.entity.Product;
import com.product.domain.entity.ProductRoute;
import com.product.domain.entity.ProductionBatch;
import com.product.domain.entity.RouteOperation;
import com.product.pps.route.RouteOperationValidator;
import com.product.pps.service.IProductRouteService;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ProductRouteServiceImpl implements IProductRouteService {

    private static final Set<String> BLOCKING_TASK_STATUSES = Set.of(
            StatusConstants.READY_OPERATION_TASK,
            StatusConstants.SCHEDULED_OPERATION_TASK,
            StatusConstants.RUNNING_OPERATION_TASK);

    @Autowired
    private RouteOperationValidator routeOperationValidator;

    @Override
    public ProductRoute getByRouteId(String routeId) {
        if (StringUtils.isEmpty(routeId)) {
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
            if (route != null && StringUtils.isNotEmpty(route.getRouteId())) {
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
                if (route != null && StringUtils.isNotEmpty(route.getRouteId())) {
                    route.setOperations(loadOperations(route.getRouteId()));
                }
            }
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String createRoute(ProductRoute route) {
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
        return route.getRouteId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateRoute(ProductRoute route) {
        if (route == null || StringUtils.isEmpty(route.getRouteId())) {
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
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void activateRoute(String routeId) {
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
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteRoute(String routeId) {
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
        if (StringUtils.isEmpty(route.getRouteId())) {
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

    private ProductRoute requireRoute(String routeId) {
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
        Product product = Db.lambdaQuery(Product.class)
                .select(Product::getProductId)
                .eq(Product::getProductId, productId)
                .one();
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

    private List<RouteOperation> loadOperations(String routeId) {
        return Db.lambdaQuery(RouteOperation.class)
                .eq(RouteOperation::getRouteId, routeId)
                .list()
                .stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(RouteOperation::getSequence, Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(RouteOperation::getOpCode, Comparator.nullsLast(String::compareTo)))
                .toList();
    }

    private void saveOperations(String routeId, List<RouteOperation> operations) {
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

    private void replaceOperations(String routeId, List<RouteOperation> operations) {
        Db.lambdaUpdate(RouteOperation.class)
                .eq(RouteOperation::getRouteId, routeId)
                .remove();
        saveOperations(routeId, operations);
    }

    private void deactivateOtherRoutes(Long productId, String activeRouteId) {
        Db.lambdaUpdate(ProductRoute.class)
                .set(ProductRoute::getIsActive, 0)
                .eq(ProductRoute::getProductId, productId)
                .ne(ProductRoute::getRouteId, activeRouteId)
                .update();
    }

    private boolean hasBlockingTasksForProduct(Long productId) {
        List<Long> orderLineIds = Db.lambdaQuery(OrderLine.class)
                .select(OrderLine::getOrderLineId)
                .eq(OrderLine::getProductId, productId)
                .list()
                .stream()
                .map(OrderLine::getOrderLineId)
                .filter(Objects::nonNull)
                .toList();
        if (CollectionUtils.isEmpty(orderLineIds)) {
            return false;
        }
        List<String> batchIds = Db.lambdaQuery(ProductionBatch.class)
                .select(ProductionBatch::getBatchId)
                .in(ProductionBatch::getOrderLineId, orderLineIds)
                .list()
                .stream()
                .map(ProductionBatch::getBatchId)
                .filter(StringUtils::isNotEmpty)
                .collect(Collectors.toList());
        if (CollectionUtils.isEmpty(batchIds)) {
            return false;
        }
        Long count = Db.lambdaQuery(OperationTask.class)
                .in(OperationTask::getBatchId, batchIds)
                .in(OperationTask::getStatus, BLOCKING_TASK_STATUSES)
                .count();
        return count != null && count > 0;
    }
}
