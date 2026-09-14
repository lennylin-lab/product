package com.product.masterdata.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.product.masterdata.domain.entity.Product;
import org.apache.ibatis.annotations.Mapper;

/**
 * Product Mapper（master_data_db，ADR-0005 database per service）。
 */
@Mapper
public interface ProductMapper extends BaseMapper<Product> {
}
