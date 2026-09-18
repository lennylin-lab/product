package com.product.demand.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.product.demand.common.utils.StringUtils;
import com.product.demand.domain.entity.Customer;
import com.product.demand.mapper.CustomerMapper;
import com.product.demand.service.ICustomerService;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 客户Service业务层处理（MyBatis-Plus；单体 product-demand CustomerServiceImpl 移植）
 *
 * @author product
 * @date 2025-12-23
 */
@Service
public class CustomerServiceImpl extends ServiceImpl<CustomerMapper, Customer> implements ICustomerService {

    /**
     * 查询客户
     *
     * @param customerId 客户主键
     * @return 客户
     */
    @Override
    public Customer selectCustomerByCustomerId(Long customerId) {
        return getById(customerId);
    }

    /**
     * 查询客户列表
     *
     * @param customer 查询条件
     * @return 客户集合
     */
    @Override
    public List<Customer> selectCustomerList(Customer customer) {
        return list(buildQueryWrapper(customer));
    }

    /**
     * 分页查询客户列表
     *
     * @param page      分页参数
     * @param customer 查询条件
     * @return 分页结果
     */
    @Override
    public Page<Customer> selectCustomerPage(Page<Customer> page, Customer customer) {
        return this.page(page, buildQueryWrapper(customer));
    }

    /**
     * 新增客户
     *
     * @param customer 客户
     * @return 是否成功
     */
    @Override
    public boolean insertCustomer(Customer customer) {
        boolean saved = save(customer);
        return saved;
    }

    /**
     * 批量新增客户
     *
     * @param customers 客户列表
     * @return 成功条数
     */
    @Override
    public int batchInsertCustomer(List<Customer> customers) {
        if (CollectionUtils.isEmpty(customers)) {
            return 0;
        }
        boolean success = saveBatch(customers);
        return success ? customers.size() : 0;
    }

    /**
     * 修改客户
     *
     * @param customer 客户
     * @return 是否成功
     */
    @Override
    public boolean updateCustomer(Customer customer) {
        boolean updated = updateById(customer);
        return updated;
    }

    /**
     * 批量删除客户
     *
     * @param customerIds 主键集合
     * @return 是否成功
     */
    @Override
    public boolean deleteCustomerByCustomerIds(Long[] customerIds) {
        if (customerIds == null || customerIds.length == 0) {
            return false;
        }
        return removeByIds(Arrays.asList(customerIds));
    }

    /**
     * 删除客户信息
     *
     * @param customerId 主键
     * @return 是否成功
     */
    @Override
    public boolean deleteCustomerByCustomerId(Long customerId) {
        return removeById(customerId);
    }

    /**
     * 构建查询条件
     */
    private LambdaQueryWrapper<Customer> buildQueryWrapper(Customer customer) {
        LambdaQueryWrapper<Customer> wrapper = new LambdaQueryWrapper<>();
        if (customer == null) {
            return wrapper;
        }
        wrapper.like(StringUtils.hasText(customer.getCustomerName()), Customer::getCustomerName, customer.getCustomerName());
        Map<String, Object> params = customer.getParams();
        String beginTime = MapUtils.getString(params, "beginTime");
        String endTime = MapUtils.getString(params, "endTime");
        if (StringUtils.hasText(beginTime)) {
            LocalDateTime start = LocalDate.parse(beginTime, DateTimeFormatter.ofPattern("yyyy-MM-dd")).atStartOfDay();
            wrapper.ge(Customer::getCreateTime, beginTime);
        }
        if (StringUtils.hasText(endTime)) {
            LocalDateTime end = LocalDate.parse(endTime, DateTimeFormatter.ofPattern("yyyy-MM-dd")).atTime(LocalTime.MAX);
            wrapper.le(Customer::getCreateTime, end);
        }
        wrapper.eq(customer.getRemark() != null, Customer::getRemark, customer.getRemark());
        return wrapper;
    }

}
