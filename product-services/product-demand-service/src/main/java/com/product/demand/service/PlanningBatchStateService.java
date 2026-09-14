package com.product.demand.service;

import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.product.demand.domain.entity.PlanningBatchState;
import com.product.demand.mapper.PlanningBatchStateMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * planning 批次状态投影服务（Phase 5；无 messaging 条件——投影清理挂接在订单行/订单
 * 删除链路，与事件开关无关）。
 *
 * <p>投影写入仅由 batch.progress.changed 消费（DemandEventConsumerService）完成；
 * 本服务提供删除（订单行/订单删除时随行清理）与查询（对账/巡检）能力。</p>
 */
@Service
@RequiredArgsConstructor
public class PlanningBatchStateService {

    private final PlanningBatchStateMapper planningBatchStateMapper;

    /** 删除订单行集合的批次投影（与行删除同事务调用，消费不到的批次不再参与聚合）。 */
    @Transactional(rollbackFor = Exception.class)
    public int deleteByOrderLineIds(List<Long> orderLineIds) {
        if (orderLineIds == null || orderLineIds.isEmpty()) {
            return 0;
        }
        return Db.lambdaUpdate(PlanningBatchState.class)
                .in(PlanningBatchState::getOrderLineId, orderLineIds)
                .remove() ? orderLineIds.size() : 0;
    }

    /** 投影行数（对账巡检）。 */
    public long count() {
        Long count = planningBatchStateMapper.selectCount(null);
        return count == null ? 0L : count;
    }
}
