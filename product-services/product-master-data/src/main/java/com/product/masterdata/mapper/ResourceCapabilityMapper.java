package com.product.masterdata.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.product.masterdata.domain.entity.ResourceCapability;
import org.apache.ibatis.annotations.Mapper;

/**
 * ResourceCapability Mapper（master_data_db，ADR-0005 database per service）。
 */
@Mapper
public interface ResourceCapabilityMapper extends BaseMapper<ResourceCapability> {
}
