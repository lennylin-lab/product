package com.product.masterdata.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.product.masterdata.domain.entity.Resource;
import org.apache.ibatis.annotations.Mapper;

/**
 * Resource Mapper（master_data_db，ADR-0005 database per service）。
 */
@Mapper
public interface ResourceMapper extends BaseMapper<Resource> {
}
