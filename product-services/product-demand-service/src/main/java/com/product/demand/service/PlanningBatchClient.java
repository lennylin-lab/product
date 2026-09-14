package com.product.demand.service;

import com.product.demand.common.exception.ServiceException;
import com.product.planning.api.PlanningBatchApi;
import com.product.planning.api.dto.PlanningContracts;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * planning 契约消费封装（Phase 4；ADR-0005：production_batch 归 planning 所有）。
 *
 * <p>两条契约的失败语义（显式声明）：</p>
 * <ul>
 *   <li><b>删行级联（写路径，fail-closed）</b>：单体删除订单行时同库级联
 *       {@code delete from production_batch where order_line_id in (...)}。服务化后该删除
 *       经 planning 契约执行；planning 不可达/超时/响应非法时<b>拒绝本次订单行删除</b>
 *       （抛 ServiceException，本地行不删）——绝不出现"行已删、批次仍占用"的静默不一致。
 *       排布顺序为先删 planning 批次、后删 demand 订单行：失败窗口收敛为
 *       "批次已删、行仍在"（可重试删除），与单体同事务级联语义方向一致；</li>
 *   <li><b>批次视图填充（只读路径，degrade-to-empty）</b>：订单行详情的
 *       productionBatchList 经本契约填充；planning 不可达时降级为空列表并告警——
 *       冻结契约"订单行详情恒可读"优先，差异（远端批次短暂不可见）已显式记录。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlanningBatchClient {

    private final PlanningBatchApi planningBatchApi;

    /**
     * 级联删除订单行下的生产批次（单体 demand 删行级联语义；失败 fail-closed）。
     */
    public void deleteBatchesByOrderLines(List<Long> orderLineIds) {
        if (CollectionUtils.isEmpty(orderLineIds)) {
            return;
        }
        PlanningContracts.DeletedBatchesResponse response;
        try {
            response = planningBatchApi.deleteBatchesByOrderLines(
                    new PlanningContracts.BatchesByOrderLinesRequest(orderLineIds));
        } catch (FeignException e) {
            log.error("计划服务调用失败，订单行级联批次清理未执行，订单行删除拒绝: orderLineIds={} status={}",
                    orderLineIds, e.status(), e);
            throw new ServiceException("计划服务不可用，无法清理生产批次，请稍后重试");
        }
        if (response == null) {
            log.error("计划服务响应非法，订单行级联批次清理未执行，订单行删除拒绝: orderLineIds={}", orderLineIds);
            throw new ServiceException("计划服务响应异常，无法清理生产批次，请稍后重试");
        }
    }

    /**
     * 查询订单行下的生产批次（只读视图；失败降级为空列表并告警）。
     */
    public List<PlanningContracts.ProductionBatchViewDTO> listBatchesByOrderLines(Long orderLineId) {
        if (orderLineId == null) {
            return Collections.emptyList();
        }
        try {
            PlanningContracts.BatchesByOrderLinesResponse response = planningBatchApi.listBatchesByOrderLines(
                    new PlanningContracts.BatchesByOrderLinesRequest(List.of(orderLineId)));
            return response == null || response.getBatches() == null
                    ? Collections.emptyList()
                    : response.getBatches();
        } catch (FeignException e) {
            log.warn("计划服务不可用，订单行详情批次视图降级为空列表: orderLineId={} status={}",
                    orderLineId, e.status());
            return Collections.emptyList();
        }
    }
}
