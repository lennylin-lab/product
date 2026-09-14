package com.product.planning.domain.model;

import lombok.Data;

/**
 * 机台-模具兼容行（Phase 4）：与单体 MachineMoldCompatibility 实体同构的纯内存模型。
 */
@Data
public class MachineMoldCompatibility {

    private Long machineId;

    private Long moldId;

    /** 是否兼容（1 兼容；null 视为兼容，与单体判定一致）。 */
    private Integer isCompatible;
}
