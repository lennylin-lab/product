package com.product.demand.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 需求域数据版本 Mapper（Phase 4：排程输入快照漂移检测的计数器）。
 */
@Mapper
public interface DemandDataVersionMapper {

    /** 在当前写事务内递增版本计数（与业务写同事务）。 */
    int bump(@Param("scope") String scope);

    /** 读取当前版本计数；表为空（从未写入）时返回 null。 */
    Long selectVersion(@Param("scope") String scope);
}
