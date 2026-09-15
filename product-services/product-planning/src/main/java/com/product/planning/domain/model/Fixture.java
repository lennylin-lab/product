package com.product.planning.domain.model;

import lombok.Data;

import java.util.List;

/**
 * 夹具排程视图（2026-09-15 夹具建模任务）：与 master_data_db fixture 扩展表同构的
 * 纯内存模型（master-data 权属，经契约加载映射；字段仅覆盖排程算法消费面）。
 */
@Data
public class Fixture {

    private Long fixtureId;

    /** 夹具业务编号。 */
    private String fixtureCode;

    /** 夹具-模具兼容行（master_data_db fixture_mold_compatibility；2026-09-15 兼容增量）。 */
    private List<FixtureMoldCompatibility> moldCompatibilityList;
}
