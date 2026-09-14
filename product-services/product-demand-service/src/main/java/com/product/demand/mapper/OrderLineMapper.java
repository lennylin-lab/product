package com.product.demand.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.product.demand.domain.entity.OrderLine;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 订单明细Mapper接口，基于 MyBatis-Plus
 *
 * <p>与单体的差异（ADR-0005 数据所有权，非行为变更）：单体本 Mapper 含
 * production_batch 子表查询/删除/批量插入（selectProductionBatchList、
 * deleteProductionBatchByBatchIds、deleteProductionBatchByBatchId、batchProductionBatch），
 * 目标态 production_batch 归 product-planning 所有（planning_db），demand 服务
 * 禁止跨服务读写他方表，故不移植这些语句。订单行删除时单体同步清理其批次的语义
 * 由 Phase 4 的 planning 契约承接（PlanningBatchClient.deleteBatchesByOrderLines）。</p>
 *
 * <p>Phase 4 新增 allocateQty/releaseQty：单体 product-pps OrderLineAllocationMapper
 * 的 SQL 逐字迁移（该 SQL 操作 order_line 表，本就属于 demand 权属；排程拆批的
 * 数量预占用经 demand 内部契约调用本方法）。</p>
 *
 * @author product
 * @date 2025-12-27
 */
@Mapper
public interface OrderLineMapper extends BaseMapper<OrderLine> {

    /**
     * 原子预占用：仅当 allocated_qty + batchQty <= qty 时更新成功（单体 SQL 逐字迁移）。
     *
     * @param orderLineId 订单行ID
     * @param batchQty    预占数量
     * @return 影响行数
     */
    int allocateQty(@Param("orderLineId") Long orderLineId, @Param("batchQty") Long batchQty);

    /**
     * 释放预占用：直接减少 allocated_qty（batchQty 为负数；单体 SQL 逐字迁移）。
     *
     * @param orderLineId 订单行ID
     * @param batchQty    释放数量（负数）
     * @return 影响行数
     */
    int releaseQty(@Param("orderLineId") Long orderLineId, @Param("batchQty") Long batchQty);
}
