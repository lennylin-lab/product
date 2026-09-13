package com.product.demand.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.product.domain.entity.OrderLine;
import com.product.domain.entity.ProductionBatch;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 订单明细Mapper接口，基于 MyBatis-Plus
 *
 * @author product
 * @date 2025-12-27
 */
@Mapper
public interface OrderLineMapper extends BaseMapper<OrderLine> {
    /**
     * 查询生产批次（订单行拆批）列表
     *
     * @param orderLineId 订单明细主键
     * @return 生产批次（订单行拆批）集合
     */
    List<ProductionBatch> selectProductionBatchList(Long orderLineId);

    /**
     * 批量删除生产批次（订单行拆批）
     *
     * @param orderLineIds 主键集合
     * @return 结果
     */
    int deleteProductionBatchByBatchIds(String[] orderLineIds);

    /**
     * 通过主键删除生产批次（订单行拆批）信息
     *
     * @param orderLineId 主键
     * @return 结果
     */
    int deleteProductionBatchByBatchId(Long orderLineId);

    /**
     * 批量新增生产批次（订单行拆批）
     *
     * @param productionBatchList 生产批次（订单行拆批）列表
     * @return 结果
     */
    int batchProductionBatch(List<ProductionBatch> productionBatchList);
}
