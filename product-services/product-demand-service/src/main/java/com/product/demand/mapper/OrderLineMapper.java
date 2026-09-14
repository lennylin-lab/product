package com.product.demand.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.product.demand.domain.entity.OrderLine;
import org.apache.ibatis.annotations.Mapper;

/**
 * 订单明细Mapper接口，基于 MyBatis-Plus
 *
 * <p>与单体的差异（ADR-0005 数据所有权，非行为变更）：单体本 Mapper 含
 * production_batch 子表查询/删除/批量插入（selectProductionBatchList、
 * deleteProductionBatchByBatchIds、deleteProductionBatchByBatchId、batchProductionBatch），
 * 目标态 production_batch 归 product-planning 所有（planning_db），demand 服务
 * 禁止跨服务读写他方表，故不移植这些语句。订单行删除时单体同步清理其批次的语义
 * 由 Phase 4 的 planning 契约/事件承接（Phase 3 中 planning_db 为空，行为等价）。</p>
 *
 * @author product
 * @date 2025-12-27
 */
@Mapper
public interface OrderLineMapper extends BaseMapper<OrderLine> {
}
