package com.product.demand.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.product.demand.domain.entity.OrderLine;

import java.util.List;

/**
 * 订单明细Service接口（MyBatis-Plus）
 *
 * @author product
 * @date 2025-12-27
 */
public interface IOrderLineService extends IService<OrderLine> {

    /**
     * 查询订单明细
     *
     * @param orderLineId 订单明细主键
     * @return 订单明细
     */
    OrderLine selectOrderLineByOrderLineId(Long orderLineId);

    /**
     * 查询订单明细列表
     *
     * @param orderLine 查询条件
     * @return 订单明细集合
     */
    List<OrderLine> selectOrderLineList(OrderLine orderLine);

    /**
     * 分页查询订单明细列表
     *
     * @param page      分页参数
     * @param orderLine 查询条件
     * @return 分页结果
     */
    Page<OrderLine> selectOrderLinePage(Page<OrderLine> page, OrderLine orderLine);

    /**
     * 新增订单明细
     *
     * @param orderLine 订单明细
     * @return 是否成功
     */
    boolean insertOrderLine(OrderLine orderLine);

    /**
     * 批量新增订单明细
     *
     * @param orderLines 订单明细列表
     * @return 成功条数
     */
    int batchInsertOrderLine(List<OrderLine> orderLines);

    /**
     * 修改订单明细
     *
     * @param orderLine 订单明细
     * @return 是否成功
     */
    boolean updateOrderLine(OrderLine orderLine);

    /**
     * 批量删除订单明细
     *
     * @param orderLineIds 主键集合
     * @return 是否成功
     */
    boolean deleteOrderLineByOrderLineIds(String[] orderLineIds);

    /**
     * 删除订单明细信息
     *
     * @param orderLineId 主键
     * @return 是否成功
     */
    boolean deleteOrderLineByOrderLineId(Long orderLineId);

    boolean release(Long orderLineId);

    boolean cancelRelease(Long orderLineId);
}
