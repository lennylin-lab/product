package com.product.demand.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.product.demand.domain.entity.Customer;

import java.util.List;

/**
 * 客户Service接口（MyBatis-Plus）
 *
 * @author product
 * @date 2025-12-23
 */
public interface ICustomerService extends IService<Customer> {

    /**
     * 查询客户
     *
     * @param customerId 客户主键
     * @return 客户
     */
    Customer selectCustomerByCustomerId(Long customerId);

    /**
     * 查询客户列表
     *
     * @param customer 查询条件
     * @return 客户集合
     */
    List<Customer> selectCustomerList(Customer customer);

    /**
     * 分页查询客户列表
     *
     * @param page      分页参数
     * @param customer 查询条件
     * @return 分页结果
     */
    Page<Customer> selectCustomerPage(Page<Customer> page, Customer customer);

    /**
     * 新增客户
     *
     * @param customer 客户
     * @return 是否成功
     */
    boolean insertCustomer(Customer customer);

    /**
     * 批量新增客户
     *
     * @param customers 客户列表
     * @return 成功条数
     */
    int batchInsertCustomer(List<Customer> customers);

    /**
     * 修改客户
     *
     * @param customer 客户
     * @return 是否成功
     */
    boolean updateCustomer(Customer customer);

    /**
     * 批量删除客户
     *
     * @param customerIds 主键集合
     * @return 是否成功
     */
    boolean deleteCustomerByCustomerIds(Long[] customerIds);

    /**
     * 删除客户信息
     *
     * @param customerId 主键
     * @return 是否成功
     */
    boolean deleteCustomerByCustomerId(Long customerId);
}
