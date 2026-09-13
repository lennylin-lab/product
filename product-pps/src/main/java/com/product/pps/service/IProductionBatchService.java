package com.product.pps.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.product.domain.dto.BatchSearchDTO;
import com.product.domain.entity.ProductionBatch;
import com.product.domain.vo.ProductionBatchVO;

import java.util.List;

/**
 * 生产批次（订单行拆批）Service接口（MyBatis-Plus）
 *
 * @author product
 * @date 2025-12-27
 */
public interface IProductionBatchService extends IService<ProductionBatch> {

    /**
     * 查询生产批次（订单行拆批）
     *
     * @param batchId 生产批次（订单行拆批）主键
     * @return 生产批次（订单行拆批）
     */
    ProductionBatch selectProductionBatchByBatchId(Long batchId);

    /**
     * 查询生产批次（订单行拆批）列表
     *
     * @param productionBatch 查询条件
     * @return 生产批次（订单行拆批）集合
     */
    List<ProductionBatch> selectProductionBatchList(ProductionBatch productionBatch);

    /**
     * 分页查询生产批次（订单行拆批）列表
     *
     * @param page            分页参数
     * @param batchSearchDTO 查询条件
     * @return 分页结果
     */
    Page<ProductionBatchVO> selectProductionBatchPage(Page<ProductionBatchVO> page, BatchSearchDTO batchSearchDTO);

    /**
     * 新增生产批次（订单行拆批）
     *
     * @param productionBatch 生产批次（订单行拆批）
     * @return 是否成功
     */
    boolean insertProductionBatch(ProductionBatch productionBatch);

    /**
     * 批量新增生产批次（订单行拆批）
     *
     * @param productionBatchs 生产批次（订单行拆批）列表
     * @return 成功条数
     */
    int batchInsertProductionBatch(List<ProductionBatch> productionBatchs);

    /**
     * 修改生产批次（订单行拆批）
     *
     * @param productionBatch 生产批次（订单行拆批）
     * @return 是否成功
     */
    boolean updateProductionBatch(ProductionBatch productionBatch);

    /**
     * 批量删除生产批次（订单行拆批）
     *
     * @param batchIds 主键集合
     * @return 是否成功
     */
    boolean deleteProductionBatchByBatchIds(Long[] batchIds);

    /**
     * 删除生产批次（订单行拆批）信息
     *
     * @param batchId 主键
     * @return 是否成功
     */
    boolean deleteProductionBatchByBatchId(Long batchId);

    boolean release(Long batchId);

    boolean cancelRelease(Long batchId);
}
