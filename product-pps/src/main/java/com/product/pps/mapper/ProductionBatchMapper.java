package com.product.pps.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.product.domain.dto.BatchSearchDTO;
import com.product.domain.entity.ProductionBatch;
import com.product.domain.vo.ProductionBatchVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 生产批次（订单行拆批）Mapper接口，基于 MyBatis-Plus
 *
 * @author product
 * @date 2025-12-27
 */
@Mapper
public interface ProductionBatchMapper extends BaseMapper<ProductionBatch> {
    Page<ProductionBatchVO> selectProductionBatchPage(Page<ProductionBatchVO> page, BatchSearchDTO batchSearchDTO);

    ProductionBatch selectBatchForUpdate(@Param("batchId") Long batchId);
}
