package com.product.masterdata.service;

import com.product.masterdata.mapper.MasterDataVersionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 主数据数据版本服务（Phase 3 新增；baselines.md §2 补充记录的版本字段方案落地）。
 *
 * <p>master_data_db 的服务权属表 master_data_data_version 维护单调递增计数：任一主数据
 * 写事务提交时 +1（bump 与业务写同事务，要么一起生效要么一起回滚）。该计数经
 * product-master-data-api 契约以 {@code snapshotVersion} 暴露，供消费方（Phase 4 排程）
 * 在输入快照加载前后对比检测数据漂移；行级版本由各 DTO 的 {@code version}
 * （update_time epoch 毫秒）承载。</p>
 *
 * <p>读取（{@link #currentVersion()}）不需要事务；写路径的 bump 由各写方法所在事务调用。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MasterDataVersionService {

    /** 当前唯一版本域：整个主数据域一个计数器（Phase 4 如需按聚合类型细分再扩展 scope）。 */
    public static final String SCOPE_MASTER_DATA = "MASTER_DATA";

    private final MasterDataVersionMapper versionMapper;

    /** 在当前写事务内递增版本计数（须与业务写同一事务方法内调用）。 */
    @Transactional
    public void bump() {
        versionMapper.bump(SCOPE_MASTER_DATA);
    }

    /** 读取当前版本计数；表为空（从未写入）时记 0。 */
    public long currentVersion() {
        Long version = versionMapper.selectVersion(SCOPE_MASTER_DATA);
        return version == null ? 0L : version;
    }
}
