package com.product.masterdata.service.impl;

import com.product.demand.api.DemandBatchQueryApi;
import com.product.demand.api.dto.AllocationCommand;
import com.product.demand.api.dto.DemandQueryRequests;
import com.product.planning.api.PlanningBatchApi;
import com.product.planning.api.dto.PlanningContracts;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * 启用工艺路线删除保护（Phase 4 接线，替代单体跨模块同库查询链）。
 *
 * <p>单体 {@code hasBlockingTasksForProduct}：order_line（按 product）→
 * production_batch（按 order_line）→ operation_task（状态 ∈ {READY, SCHEDULED,
 * RUNNING}）计数 &gt; 0。服务化后三段数据分属三个服务，本组件经契约还原同一语义：</p>
 * <ol>
 *   <li>demand：{@code listOrderLineIdsByProduct(productId)}（demand_batch_query 契约）；</li>
 *   <li>planning：{@code hasBlockingTasks(orderLineIds)}（planning_batch 契约）。</li>
 * </ol>
 *
 * <p><b>失败语义（显式声明，单体无对应场景）</b>：任一依赖不可达/超时/响应非法时按
 * fail-closed 处理——删除启用中路线的操作以 ServiceException 拒绝（无法确认"无阻塞
 * 任务"即视为存在阻塞），绝不静默放行；文案为新增显式差异（单体同库查询不存在远程失败）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RouteDeleteGuard {

    private final DemandBatchQueryApi demandBatchQueryApi;
    private final PlanningBatchApi planningBatchApi;

    /**
     * 与单体 ProductRouteServiceImpl#hasBlockingTasksForProduct 同语义的跨服务实现。
     */
    public boolean hasBlockingTasksForProduct(Long productId) {
        if (productId == null) {
            return false;
        }
        AllocationCommand.OrderLineIdsByProductResponse orderLineIds;
        try {
            orderLineIds = demandBatchQueryApi.listOrderLineIdsByProduct(
                    new DemandQueryRequests.OrderLineIdsByProductRequest(productId));
        } catch (FeignException e) {
            log.error("需求服务调用失败，启用路线删除保护按阻塞处理: productId={} status={}",
                    productId, e.status(), e);
            throw new com.product.masterdata.common.exception.ServiceException(
                    "需求服务不可用，无法确认工艺路线删除保护，请稍后重试");
        }
        if (orderLineIds == null || orderLineIds.getOrderLineIds() == null) {
            log.error("需求服务响应非法，启用路线删除保护按阻塞处理: productId={}", productId);
            throw new com.product.masterdata.common.exception.ServiceException(
                    "需求服务响应异常，无法确认工艺路线删除保护，请稍后重试");
        }
        List<Long> ids = orderLineIds.getOrderLineIds().stream().filter(Objects::nonNull).toList();
        if (CollectionUtils.isEmpty(ids)) {
            return false;
        }
        PlanningContracts.BlockingTasksResponse blocking;
        try {
            blocking = planningBatchApi.hasBlockingTasks(new PlanningContracts.BatchesByOrderLinesRequest(ids));
        } catch (FeignException e) {
            log.error("计划服务调用失败，启用路线删除保护按阻塞处理: productId={} status={}",
                    productId, e.status(), e);
            throw new com.product.masterdata.common.exception.ServiceException(
                    "计划服务不可用，无法确认工艺路线删除保护，请稍后重试");
        }
        if (blocking == null) {
            log.error("计划服务响应非法，启用路线删除保护按阻塞处理: productId={}", productId);
            throw new com.product.masterdata.common.exception.ServiceException(
                    "计划服务响应异常，无法确认工艺路线删除保护，请稍后重试");
        }
        return blocking.isBlocking();
    }
}
