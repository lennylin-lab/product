package com.product.demand.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.product.demand.domain.entity.PlanningBatchState;
import org.apache.ibatis.annotations.Mapper;

/**
 * planning 批次状态投影 Mapper（Phase 5；demand_db 服务权属表，事件溯源投影）。
 */
@Mapper
public interface PlanningBatchStateMapper extends BaseMapper<PlanningBatchState> {
}
