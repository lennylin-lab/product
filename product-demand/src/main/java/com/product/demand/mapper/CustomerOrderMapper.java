package com.product.demand.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.product.domain.entity.CustomerOrder;
import com.product.domain.entity.OrderLine;
import com.product.domain.vo.CustomerOrderVO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 订单Mapper接口，基于 MyBatis-Plus
 *
 * @author product
 * @date 2025-12-26
 */
@Mapper
public interface CustomerOrderMapper extends BaseMapper<CustomerOrder> {
    /**
     * 查询订单明细列表
     *
     * @param orderId 订单主键
     * @return 订单明细集合
     */
    List<OrderLine> selectOrderLineList(Long orderId);

    /**
     * 批量删除订单明细
     *
     * @param orderIds 主键集合
     * @return 结果
     */
    int deleteOrderLineByOrderIds(String[] orderIds);

    /**
     * 通过主键删除订单明细信息
     *
     * @param orderId 主键
     * @return 结果
     */
    int deleteOrderLineByOrderId(Long orderId);

    /**
     * 批量新增订单明细
     *
     * @param orderLineList 订单明细列表
     * @return 结果
     */
    int batchOrderLine(List<OrderLine> orderLineList);

    Page<CustomerOrderVO> selectCustomerOrderPage(Page<CustomerOrderVO> page, CustomerOrder customerOrder);
}
