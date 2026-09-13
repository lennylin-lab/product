package com.product.demand.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.product.common.constant.StatusConstants;
import com.product.common.exception.ServiceException;
import com.product.common.utils.StringUtils;
import com.product.demand.mapper.OrderLineMapper;
import com.product.demand.service.IOrderLineService;
import com.product.domain.entity.CustomerOrder;
import com.product.domain.entity.OrderLine;
import com.product.domain.entity.ProductionBatch;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
/**
 * 订单明细Service业务层处理（MyBatis-Plus）
 *
 * @author product
 * @date 2025-12-27
 */
@Service
public class OrderLineServiceImpl extends ServiceImpl<OrderLineMapper, OrderLine> implements IOrderLineService {

    /**
     * 查询订单明细
     *
     * @param orderLineId 订单明细主键
     * @return 订单明细
     */
    @Override
    public OrderLine selectOrderLineByOrderLineId(Long orderLineId) {
        OrderLine orderLine = getById(orderLineId);
        if (orderLine == null) {
            return null;
        }
        List<ProductionBatch> productionBatchList = baseMapper.selectProductionBatchList(orderLineId);
        orderLine.setProductionBatchList(productionBatchList);
        return orderLine;
    }

    /**
     * 查询订单明细列表
     *
     * @param orderLine 查询条件
     * @return 订单明细集合
     */
    @Override
    public List<OrderLine> selectOrderLineList(OrderLine orderLine) {
        return list(buildQueryWrapper(orderLine));
    }

    /**
     * 分页查询订单明细列表
     *
     * @param page      分页参数
     * @param orderLine 查询条件
     * @return 分页结果
     */
    @Override
    public Page<OrderLine> selectOrderLinePage(Page<OrderLine> page, OrderLine orderLine) {
        return this.page(page, buildQueryWrapper(orderLine));
    }

    /**
     * 新增订单明细
     *
     * @param orderLine 订单明细
     * @return 是否成功
     */
    @Override
    public boolean insertOrderLine(OrderLine orderLine) {
        validateOrderExists(orderLine == null ? null : orderLine.getOrderId());
        if (StringUtils.isEmpty(orderLine.getStatus())) {
            orderLine.setStatus(StatusConstants.NEW_ORDER_LINE);
        }
        boolean saved = save(orderLine);
        return saved;
    }

    /**
     * 批量新增订单明细
     *
     * @param orderLines 订单明细列表
     * @return 成功条数
     */
    @Override
    public int batchInsertOrderLine(List<OrderLine> orderLines) {
        if (CollectionUtils.isEmpty(orderLines)) {
            return 0;
        }
        orderLines.forEach(orderLine -> validateOrderExists(orderLine.getOrderId()));
        boolean success = saveBatch(orderLines);
        return success ? orderLines.size() : 0;
    }

    /**
     * 修改订单明细
     *
     * @param orderLine 订单明细
     * @return 是否成功
     */
    @Override
    public boolean updateOrderLine(OrderLine orderLine) {
        boolean updated = updateById(orderLine);
        return updated;
    }

    /**
     * 批量删除订单明细
     *
     * @param orderLineIds 主键集合
     * @return 是否成功
     */
    @Override
    public boolean deleteOrderLineByOrderLineIds(String[] orderLineIds) {
        if (orderLineIds == null || orderLineIds.length == 0) {
            return false;
        }
        baseMapper.deleteProductionBatchByBatchIds(orderLineIds);
        return removeByIds(Arrays.asList(orderLineIds));
    }

    /**
     * 删除订单明细信息
     *
     * @param orderLineId 主键
     * @return 是否成功
     */
    @Override
    public boolean deleteOrderLineByOrderLineId(Long orderLineId) {
        baseMapper.deleteProductionBatchByBatchId(orderLineId);
        return removeById(orderLineId);
    }

    @Override
    public boolean release(Long orderLineId) {
        OrderLine orderLine = lambdaQuery().select(OrderLine::getOrderId, OrderLine::getStatus)
                .eq(OrderLine::getOrderLineId, orderLineId)
                .last("limit 1").one();
        String status = orderLine.getStatus();
        System.out.println(orderLine.getOrderId());
        CustomerOrder customerOrder = Db.lambdaQuery(CustomerOrder.class)
                .select(CustomerOrder::getStatus)
                .eq(CustomerOrder::getOrderId, orderLine.getOrderId())
                .last("limit 1")
                .one();
        if (customerOrder.getStatus().equals(StatusConstants.NEW_CUSTOMER_ORDER)) {
            throw new ServiceException("订单未确认");
        }
        if (status.equals(StatusConstants.IN_PRODUCTION_ORDER_LINE)) {
            throw new ServiceException("该订单行已投入生产");
        }
        if (status.equals(StatusConstants.DONE_ORDER_LINE)) {
            throw new ServiceException(("该订单行已完成"));
        }
        return lambdaUpdate().set(OrderLine::getStatus, StatusConstants.RELEASED_ORDER_LINE)
                .eq(OrderLine::getOrderLineId, orderLineId)
                .update();
    }

    @Override
    public boolean cancelRelease(Long orderLineId) {
        OrderLine orderLine = lambdaQuery().select(OrderLine::getOrderId, OrderLine::getStatus)
                .eq(OrderLine::getOrderLineId, orderLineId)
                .last("limit 1").one();
        String status = orderLine.getStatus();
        if (status.equals(StatusConstants.IN_PRODUCTION_ORDER_LINE)) {
            throw new ServiceException("该订单行已投入生产");
        }
        if (status.equals(StatusConstants.DONE_ORDER_LINE)) {
            throw new ServiceException(("该订单行已完成"));
        }
        return lambdaUpdate().set(OrderLine::getStatus, StatusConstants.NEW_ORDER_LINE)
                .eq(OrderLine::getOrderLineId, orderLineId)
                .update();
    }

    /**
     * 构建查询条件
     */
    private LambdaQueryWrapper<OrderLine> buildQueryWrapper(OrderLine orderLine) {
        LambdaQueryWrapper<OrderLine> wrapper = new LambdaQueryWrapper<>();
        if (orderLine == null) {
            return wrapper;
        }
        wrapper.eq(orderLine.getOrderId() != null, OrderLine::getOrderId, orderLine.getOrderId());
        wrapper.eq(orderLine.getProductId() != null, OrderLine::getProductId, orderLine.getProductId());
        wrapper.eq(orderLine.getQty() != null, OrderLine::getQty, orderLine.getQty());
        wrapper.eq(orderLine.getStatus() != null, OrderLine::getStatus, orderLine.getStatus());
        return wrapper;
    }

    /**
     * 校验订单行归属的订单必须存在，避免明细指向不存在的订单。
     */
    private void validateOrderExists(Long orderId) {
        if (orderId == null) {
            throw new ServiceException("订单行必须指定所属订单ID");
        }
        boolean orderExists = Db.lambdaQuery(CustomerOrder.class)
                .eq(CustomerOrder::getOrderId, orderId)
                .exists();
        if (!orderExists) {
            throw new ServiceException("所属订单不存在: " + orderId);
        }
    }
}
