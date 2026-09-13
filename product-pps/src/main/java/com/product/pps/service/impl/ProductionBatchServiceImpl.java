package com.product.pps.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.product.common.constant.StatusConstants;
import com.product.common.exception.ServiceException;
import com.product.domain.dto.BatchSearchDTO;
import com.product.domain.entity.OrderLine;
import com.product.domain.entity.ProductionBatch;
import com.product.domain.vo.ProductionBatchVO;
import com.product.pps.mapper.OrderLineAllocationMapper;
import com.product.pps.mapper.ProductionBatchMapper;
import com.product.pps.service.IProductionBatchService;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 生产批次（订单行拆批）Service业务层处理（MyBatis-Plus）
 *
 * @author product
 * @date 2025-12-27
 */
@Service
public class ProductionBatchServiceImpl extends ServiceImpl<ProductionBatchMapper, ProductionBatch> implements IProductionBatchService {
    @Autowired
    private ProductionBatchMapper productionBatchMapper;
    @Autowired
    private OrderLineAllocationMapper orderLineAllocationMapper;

    /**
     * 查询生产批次（订单行拆批）
     *
     * @param batchId 生产批次（订单行拆批）主键
     * @return 生产批次（订单行拆批）
     */
    @Override
    public ProductionBatch selectProductionBatchByBatchId(Long batchId) {
        return getById(batchId);
    }

    /**
     * 查询生产批次（订单行拆批）列表
     *
     * @param productionBatch 查询条件
     * @return 生产批次（订单行拆批）集合
     */
    @Override
    public List<ProductionBatch> selectProductionBatchList(ProductionBatch productionBatch) {
        return list(buildQueryWrapper(productionBatch));
    }

    /**
     * 分页查询生产批次（订单行拆批）列表
     *
     * @param page           分页参数
     * @param batchSearchDTO 查询条件
     * @return 分页结果
     */
    @Override
    public Page<ProductionBatchVO> selectProductionBatchPage(Page<ProductionBatchVO> page, BatchSearchDTO batchSearchDTO) {
        return productionBatchMapper.selectProductionBatchPage(page, batchSearchDTO);
    }

    /**
     * 新增生产批次（订单行拆批）
     *
     * @param productionBatch 生产批次（订单行拆批）
     * @return 是否成功
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean insertProductionBatch(ProductionBatch productionBatch) {
        if (productionBatch == null || productionBatch.getOrderLineId() == null || productionBatch.getBatchQty() == null) {
            throw new ServiceException("订单行ID和批次数量不能为空");
        }
        if (productionBatch.getStatus() == null) {
            productionBatch.setStatus(StatusConstants.PLANNED_PRODUCTION_BATCH);
        }
        int affected = orderLineAllocationMapper.allocateQty(productionBatch.getOrderLineId(), productionBatch.getBatchQty());
        if (affected != 1) {
            throw new ServiceException("订单行可用数量不足，无法拆批");
        }
        boolean saved = save(productionBatch);
        if (!saved) {
            throw new ServiceException("创建生产批次失败");
        }
        return true;
    }

    /**
     * 批量新增生产批次（订单行拆批）
     *
     * @param productionBatchs 生产批次（订单行拆批）列表
     * @return 成功条数
     */
    @Override
    public int batchInsertProductionBatch(List<ProductionBatch> productionBatchs) {
        if (CollectionUtils.isEmpty(productionBatchs)) {
            return 0;
        }
        boolean success = saveBatch(productionBatchs);
        return success ? productionBatchs.size() : 0;
    }

    /**
     * 修改生产批次（订单行拆批）
     *
     * @param productionBatch 生产批次（订单行拆批）
     * @return 是否成功
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateProductionBatch(ProductionBatch productionBatch) {
        if (productionBatch == null || productionBatch.getBatchId() == null || productionBatch.getBatchQty() == null) {
            throw new ServiceException("批次ID和批次数量不能为空");
        }
        // 进行sql行锁
        ProductionBatch locked = productionBatchMapper.selectBatchForUpdate(productionBatch.getBatchId());
        if (locked == null) {
            throw new ServiceException("生产批次不存在，无法修改");
        }
        Long orderLineId = locked.getOrderLineId();
        if (productionBatch.getOrderLineId() != null && !productionBatch.getOrderLineId().equals(orderLineId)) {
            throw new ServiceException("不允许修改来源订单行");
        }
        long oldQty = locked.getBatchQty() == null ? 0L : locked.getBatchQty();
        long newQty = productionBatch.getBatchQty();
        long delta = newQty - oldQty;
        if (delta > 0) {
            int affected = orderLineAllocationMapper.allocateQty(orderLineId, delta);
            if (affected != 1) {
                throw new ServiceException("订单行可用数量不足，无法增加批次数量");
            }
        } else if (delta < 0) {
            int affected = orderLineAllocationMapper.releaseQty(orderLineId, delta);
            if (affected != 1) {
                throw new ServiceException("释放订单行占用失败");
            }
        }
        productionBatch.setOrderLineId(orderLineId);
        return updateById(productionBatch);
    }

    /**
     * 批量删除生产批次（订单行拆批）
     *
     * @param batchIds 主键集合
     * @return 是否成功
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteProductionBatchByBatchIds(Long[] batchIds) {
        if (batchIds == null || batchIds.length == 0) {
            return false;
        }
        for (Long batchId : batchIds) {
            deleteOneWithRelease(batchId);
        }
        return true;
    }

    /**
     * 删除生产批次（订单行拆批）信息
     *
     * @param batchId 主键
     * @return 是否成功
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteProductionBatchByBatchId(Long batchId) {
        return deleteOneWithRelease(batchId);
    }

    @Override
    public boolean release(Long batchId) {
        ProductionBatch productionBatch = lambdaQuery()
                .select(ProductionBatch::getOrderLineId, ProductionBatch::getStatus)
                .eq(ProductionBatch::getBatchId, batchId)
                .last("limit 1").one();
        OrderLine orderLine = Db.lambdaQuery(OrderLine.class)
                .select(OrderLine::getStatus)
                .eq(OrderLine::getOrderLineId, productionBatch.getOrderLineId())
                .last("limit 1").one();
        String status = productionBatch.getStatus();
        if (orderLine.getStatus().equals(StatusConstants.NEW_ORDER_LINE)) {
            throw new ServiceException("订单行未发布");
        }
        if (status.equals(StatusConstants.IN_PROCESS_PRODUCTION_BATCH)) {
            throw new ServiceException("该批次在执行中");
        }
        if (status.equals(StatusConstants.DONE_PRODUCTION_BATCH)) {
            throw new ServiceException("该批次已完成");
        }
        return lambdaUpdate().set(ProductionBatch::getStatus, StatusConstants.RELEASED_PRODUCTION_BATCH)
                .eq(ProductionBatch::getBatchId, batchId)
                .update();
    }

    @Override
    public boolean cancelRelease(Long batchId) {
        ProductionBatch productionBatch = lambdaQuery()
                .select(ProductionBatch::getStatus)
                .eq(ProductionBatch::getBatchId, batchId)
                .last("limit 1").one();
        String status = productionBatch.getStatus();
        if (status.equals(StatusConstants.IN_PROCESS_PRODUCTION_BATCH)) {
            throw new ServiceException("该批次在执行中");
        }
        if (status.equals(StatusConstants.DONE_PRODUCTION_BATCH)) {
            throw new ServiceException("该批次已完成");
        }
        return lambdaUpdate().set(ProductionBatch::getStatus, StatusConstants.PLANNED_PRODUCTION_BATCH)
                .eq(ProductionBatch::getBatchId, batchId)
                .update();
    }

    private boolean deleteOneWithRelease(Long batchId) {
        if (batchId == null) {
            return false;
        }
        // sql主键行锁
        ProductionBatch locked = productionBatchMapper.selectBatchForUpdate(batchId);
        if (locked == null) {
            return false;
        }
        Long orderLineId = locked.getOrderLineId();
        long batchQty = locked.getBatchQty() == null ? 0L : locked.getBatchQty();
        if (batchQty != 0L) {
            // 减去该批次数量
            int affected = orderLineAllocationMapper.releaseQty(orderLineId, -batchQty);
            if (affected != 1) {
                throw new ServiceException("释放订单行占用失败");
            }
        }
        return removeById(batchId);
    }

    /**
     * 构建查询条件
     */
    private LambdaQueryWrapper<ProductionBatch> buildQueryWrapper(ProductionBatch productionBatch) {
        LambdaQueryWrapper<ProductionBatch> wrapper = new LambdaQueryWrapper<>();
        if (productionBatch == null) {
            return wrapper;
        }
        wrapper.eq(productionBatch.getOrderLineId() != null, ProductionBatch::getOrderLineId, productionBatch.getOrderLineId());
        wrapper.eq(productionBatch.getBatchQty() != null, ProductionBatch::getBatchQty, productionBatch.getBatchQty());
        wrapper.eq(productionBatch.getStatus() != null, ProductionBatch::getStatus, productionBatch.getStatus());
        Object beginPlannedStart = productionBatch.getParams().get("beginPlannedStart");
        Object endPlannedStart = productionBatch.getParams().get("endPlannedStart");
        wrapper.between(beginPlannedStart != null && endPlannedStart != null, ProductionBatch::getPlannedStart, beginPlannedStart, endPlannedStart);
        Object beginPlannedEnd = productionBatch.getParams().get("beginPlannedEnd");
        Object endPlannedEnd = productionBatch.getParams().get("endPlannedEnd");
        wrapper.between(beginPlannedEnd != null && endPlannedEnd != null, ProductionBatch::getPlannedEnd, beginPlannedEnd, endPlannedEnd);
        return wrapper;
    }

}
