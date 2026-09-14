package com.product.masterdata.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 主数据数据版本 Mapper（master_data_data_version，服务权属表）。
 */
@Mapper
public interface MasterDataVersionMapper {

    /** 原子递增：不存在则初始化为 1，存在则 +1。须在主数据写事务内调用。 */
    int bump(@Param("scope") String scope);

    Long selectVersion(@Param("scope") String scope);
}
