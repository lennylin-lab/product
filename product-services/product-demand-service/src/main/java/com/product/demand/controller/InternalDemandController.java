package com.product.demand.controller;

import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.product.demand.api.DemandBatchQueryApi;
import com.product.demand.api.dto.AllocationCommand;
import com.product.demand.api.dto.DemandQueryRequests;
import com.product.demand.api.dto.OrderDTO;
import com.product.demand.api.dto.OrderLineDTO;
import com.product.demand.common.exception.ServiceException;
import com.product.demand.domain.entity.CustomerOrder;
import com.product.demand.domain.entity.OrderLine;
import com.product.demand.mapper.OrderLineMapper;
import com.product.demand.service.DemandDataVersionService;
import lombok.RequiredArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * 需求域内部契约端点（product-demand-api 的 DemandBatchQueryApi 实现，Phase 4）。
 *
 * <p>鉴权与其余端点一致：调用方透传用户 JWT（或异步排程的 Identity 服务身份令牌），
 * 本地验签（ADR-0003 两层校验）；无有效 token 一律 401（防伪造内部头）。
 * allocate 为写命令：语义与单体 OrderLineAllocationMapper.allocateQty/releaseQty
 * 逐字一致，与 demand_data_version bump 同事务。</p>
 */
@RestController
@RequestMapping("/internal/demand")
@RequiredArgsConstructor
public class InternalDemandController implements DemandBatchQueryApi {

    private final OrderLineMapper orderLineMapper;
    private final DemandDataVersionService versionService;

    @Override
    public OrderDTO.OrderLineBatchResponse getOrderLines(
            @RequestBody DemandQueryRequests.OrderLineBatchQueryRequest request) {
        List<Long> ids = request == null ? null : request.getOrderLineIds();
        if (ids != null && !ids.isEmpty()) {
            requireIds(ids, "订单行ID集合不能为空");
        }
        List<OrderLine> orderLines = Db.lambdaQuery(OrderLine.class)
                .in(ids != null && !ids.isEmpty(), OrderLine::getOrderLineId, ids == null ? null : ids)
                .list();
        OrderDTO.OrderLineBatchResponse response = new OrderDTO.OrderLineBatchResponse();
        response.setSnapshotVersion(versionService.currentVersion());
        response.setOrderLines(orderLines.stream()
                .filter(Objects::nonNull)
                .map(line -> {
                    OrderLineDTO dto = new OrderLineDTO();
                    dto.setOrderLineId(line.getOrderLineId());
                    dto.setOrderId(line.getOrderId());
                    dto.setProductId(line.getProductId());
                    dto.setQty(line.getQty());
                    dto.setAllocatedQty(line.getAllocatedQty());
                    dto.setStatus(line.getStatus());
                    // order_line 无时间戳列（单体基线如此）：行级版本恒 0，行变化由
                    // 响应信封 snapshotVersion（demand_data_version）承载
                    dto.setVersion(0L);
                    return dto;
                })
                .toList());
        return response;
    }

    @Override
    public OrderDTO.OrderBatchResponse getOrders(
            @RequestBody DemandQueryRequests.OrderBatchQueryRequest request) {
        List<Long> ids = request == null ? null : request.getOrderIds();
        if (ids != null && !ids.isEmpty()) {
            requireIds(ids, "订单ID集合不能为空");
        }
        List<CustomerOrder> orders = Db.lambdaQuery(CustomerOrder.class)
                .in(ids != null && !ids.isEmpty(), CustomerOrder::getOrderId, ids == null ? null : ids)
                .list();
        OrderDTO.OrderBatchResponse response = new OrderDTO.OrderBatchResponse();
        response.setSnapshotVersion(versionService.currentVersion());
        response.setOrders(orders.stream()
                .filter(Objects::nonNull)
                .map(order -> {
                    OrderDTO dto = new OrderDTO();
                    dto.setOrderId(order.getOrderId());
                    dto.setDueDate(order.getDueDate());
                    dto.setPriority(order.getPriority());
                    dto.setStatus(order.getStatus());
                    return dto;
                })
                .toList());
        return response;
    }

    @Override
    public AllocationCommand.OrderLineIdsByProductResponse listOrderLineIdsByProduct(
            @RequestBody DemandQueryRequests.OrderLineIdsByProductRequest request) {
        if (request == null || request.getProductId() == null) {
            throw new ServiceException("产品ID不能为空");
        }
        List<Long> orderLineIds = Db.lambdaQuery(OrderLine.class)
                .select(OrderLine::getOrderLineId)
                .eq(OrderLine::getProductId, request.getProductId())
                .list()
                .stream()
                .map(OrderLine::getOrderLineId)
                .filter(Objects::nonNull)
                .toList();
        AllocationCommand.OrderLineIdsByProductResponse response = new AllocationCommand.OrderLineIdsByProductResponse();
        response.setSnapshotVersion(versionService.currentVersion());
        response.setOrderLineIds(orderLineIds);
        return response;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AllocationCommand.AllocationResponse allocate(@RequestBody AllocationCommand command) {
        if (command == null || command.getOrderLineId() == null || command.getDeltaQty() == null) {
            throw new ServiceException("订单行ID和数量不能为空");
        }
        if (AllocationCommand.MODE_ALLOCATE.equals(command.getMode())) {
            int updated = orderLineMapper.allocateQty(command.getOrderLineId(), command.getDeltaQty());
            if (updated == 1) {
                versionService.bump();
            }
            return new AllocationCommand.AllocationResponse(updated);
        }
        if (AllocationCommand.MODE_RELEASE.equals(command.getMode())) {
            int updated = orderLineMapper.releaseQty(command.getOrderLineId(), command.getDeltaQty());
            if (updated == 1) {
                versionService.bump();
            }
            return new AllocationCommand.AllocationResponse(updated);
        }
        throw new ServiceException("不支持的数量预占用模式: " + command.getMode());
    }

    @Override
    public AllocationCommand.DataVersionResponse getDataVersion() {
        return new AllocationCommand.DataVersionResponse(versionService.currentVersion());
    }

    private void requireIds(List<Long> ids, String emptyMessage) {
        if (ids == null || ids.isEmpty()) {
            throw new ServiceException(emptyMessage);
        }
        if (ids.size() > DemandQueryRequests.MAX_IDS) {
            throw new ServiceException("批量查询ID数超限: " + ids.size() + " > " + DemandQueryRequests.MAX_IDS);
        }
        if (new LinkedHashSet<>(ids).size() != ids.size()) {
            throw new ServiceException("批量查询ID存在重复");
        }
    }
}
