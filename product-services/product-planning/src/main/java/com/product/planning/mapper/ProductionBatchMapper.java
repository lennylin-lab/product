package com.product.planning.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.product.planning.domain.dto.BatchSearchDTO;
import com.product.planning.domain.entity.ProductionBatch;
import com.product.planning.domain.vo.ProductionBatchVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 生产批次（订单行拆批）Mapper接口，基于 MyBatis-Plus
 *
 * @author product
 * @date 2025-12-27
 */
@Mapper
public interface ProductionBatchMapper extends BaseMapper<ProductionBatch> {
    /**
     * 本库分页查询批次（Phase 4 修复：单体版本 LEFT JOIN order_line/customer_order/product
     * 跨库表；order_line_id 过滤由服务层经 demand 契约解析后以 {@code orderLineIds} 传入，
     * 跨域聚合字段由服务层批量契约填充）。
     *
     * @param orderLineIds  null 表示不按订单行过滤；非空时仅返回这些订单行的批次
     */
    Page<ProductionBatchVO> selectProductionBatchPage(Page<ProductionBatchVO> page,
                                                      @Param("batchSearchDTO") BatchSearchDTO batchSearchDTO,
                                                      @Param("orderLineIds") List<Long> orderLineIds);

    ProductionBatch selectBatchForUpdate(@Param("batchId") Long batchId);
}
