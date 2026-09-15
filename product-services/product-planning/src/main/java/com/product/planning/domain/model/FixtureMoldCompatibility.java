package com.product.planning.domain.model;

import lombok.Data;

/**
 * 夹具-模具兼容行（2026-09-15 夹具兼容任务）：与 master_data_db
 * fixture_mold_compatibility 表同构的纯内存模型（结构镜像 {@link MachineMoldCompatibility}）。
 *
 * <p>显式允许清单语义（父任务 KD1）：{@code isCompatible=1} 才允许；所选模具无任何
 * 兼容行或兼容行为 0 → 该任务对夹具不可满足（裁决在排程计算器，本模型只承载数据）。</p>
 */
@Data
public class FixtureMoldCompatibility {

    private Long fixtureId;

    private Long moldId;

    /** 是否兼容（1 兼容；0 不兼容；显式允许清单语义，缺失行不视为兼容）。 */
    private Integer isCompatible;
}
