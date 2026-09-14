package com.product.planning.domain.model;

import lombok.Data;

import java.util.List;

/**
 * 机台排程视图（Phase 4）：与单体 Machine 实体同构的纯内存模型（master_data_db 权属，
 * 经契约加载映射；字段仅覆盖排程算法消费面）。
 */
@Data
public class Machine {

    private Long machineId;

    private Integer tonnage;

    /** 默认换型准备时间（分钟）。 */
    private Integer defaultSetupTimeMin;

    /** 机台-模具兼容性行（master_data_db machine_mold_compatibility）。 */
    private List<MachineMoldCompatibility> moldCompatibilityList;
}
