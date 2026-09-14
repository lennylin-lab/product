package com.product.demand.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.product.demand.domain.entity.Customer;
import org.apache.ibatis.annotations.Mapper;

/**
 * 客户Mapper接口，基于 MyBatis-Plus
 *
 * @author product
 * @date 2025-12-23
 */
@Mapper
public interface CustomerMapper extends BaseMapper<Customer> {
}
