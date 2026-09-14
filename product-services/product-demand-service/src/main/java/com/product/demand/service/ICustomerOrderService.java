package com.product.demand.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.product.demand.domain.entity.CustomerOrder;
import com.product.demand.domain.vo.CustomerOrderVO;

import java.util.List;

/**
 * 订单Service接口（MyBatis-Plus）
 *
 * @author product
 * @date 2025-12-26
 */
public interface ICustomerOrderService extends IService<CustomerOrder> {

    /**
     * 查询订单
     *
     * @param orderId 订单主键
     * @return 订单
     */
    CustomerOrder selectCustomerOrderByOrderId(Long orderId);

    /**
     * 查询订单列表
     *
     * @param customerOrder 查询条件
     * @return 订单集合
     */
    List<CustomerOrder> selectCustomerOrderList(CustomerOrder customerOrder);

    /**
     * 分页查询订单列表
     *
     * @param page      分页参数
     * @param customerOrder 查询条件
     * @return 分页结果
     */
    Page<CustomerOrderVO> selectCustomerOrderPage(Page<CustomerOrderVO> page, CustomerOrder customerOrder);

    /**
     * 新增订单
     *
     * @param customerOrder 订单
     * @return 是否成功
     */
    boolean insertCustomerOrder(CustomerOrder customerOrder);

    /**
     * 批量新增订单
     *
     * @param customerOrders 订单列表
     * @return 成功条数
     */
    int batchInsertCustomerOrder(List<CustomerOrder> customerOrders);

    /**
     * 修改订单
     *
     * @param customerOrder 订单
     * @return 是否成功
     */
    boolean updateCustomerOrder(CustomerOrder customerOrder);

    /**
     * 批量删除订单
     *
     * @param orderIds 主键集合
     * @return 是否成功
     */
    boolean deleteCustomerOrderByOrderIds(String[] orderIds);

    /**
     * 删除订单信息
     *
     * @param orderId 主键
     * @return 是否成功
     */
    boolean deleteCustomerOrderByOrderId(Long orderId);

    boolean check(Long orderId);

    boolean cancelCheck(Long orderId);
}
