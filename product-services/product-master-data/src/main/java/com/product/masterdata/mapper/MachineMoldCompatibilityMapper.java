package com.product.masterdata.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.product.masterdata.domain.entity.MachineMoldCompatibility;
import org.apache.ibatis.annotations.Mapper;

/**
 * MachineMoldCompatibility Mapper（master_data_db，ADR-0005 database per service）。
 */
@Mapper
public interface MachineMoldCompatibilityMapper extends BaseMapper<MachineMoldCompatibility> {
}
