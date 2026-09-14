package com.product.demand.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.product.demand.mapper.DemandDataVersionMapper;

/**
 * 需求域数据版本服务（Phase 4 新增；与 master_data_db 的 MasterDataVersionService 同款权属逻辑）。
 *
 * <p>demand_db 的服务权属表 demand_data_version 维护单调递增计数：订单/订单行任一写
 * 事务提交时 +1（bump 与业务写同事务，要么一起生效要么一起回滚）。该计数经
 * product-demand-api 契约以 {@code snapshotVersion} 暴露，供 product-planning 排程在
 * 输入快照加载前后对比检测数据漂移。客户（customer）非排程输入，不触发 bump。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DemandDataVersionService {

    /** 当前唯一版本域：整个需求域一个计数器。 */
    public static final String SCOPE_DEMAND = "DEMAND";

    private final DemandDataVersionMapper versionMapper;

    /** 在当前写事务内递增版本计数（须与业务写同一事务方法内调用）。 */
    @Transactional
    public void bump() {
        versionMapper.bump(SCOPE_DEMAND);
    }

    /** 读取当前版本计数；表为空（从未写入）时记 0。 */
    public long currentVersion() {
        Long version = versionMapper.selectVersion(SCOPE_DEMAND);
        return version == null ? 0L : version;
    }
}
