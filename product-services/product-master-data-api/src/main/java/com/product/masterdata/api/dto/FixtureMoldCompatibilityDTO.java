package com.product.masterdata.api.dto;

import java.io.Serializable;

/**
 * 夹具-模具兼容性契约条目（2026-09-15 夹具兼容任务新增，结构镜像
 * {@link MachineMoldCompatibilityDTO}）。
 *
 * <p>挂在 {@link ResourceDTO.FixtureDTO#getMoldCompatibilities()} 随 resources/batch
 * 契约下发（additive 契约变更，旧消费者不读取即不受影响）。字段与
 * fixture_mold_compatibility 表一致。兼容语义为显式允许清单（父任务 KD1）：
 * {@code isCompatible=1} 才允许；所选模具无任何兼容行或兼容行为 0 → 该任务对夹具
 * 不可满足（裁决在 planning 排程计算器，本 DTO 只承载数据）。</p>
 */
public class FixtureMoldCompatibilityDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long fixtureId;

    private Long moldId;

    /** 是否兼容（1 兼容；0 不兼容；显式允许清单语义，缺失行不视为兼容）。 */
    private Integer isCompatible;

    public Long getFixtureId() {
        return fixtureId;
    }

    public void setFixtureId(Long fixtureId) {
        this.fixtureId = fixtureId;
    }

    public Long getMoldId() {
        return moldId;
    }

    public void setMoldId(Long moldId) {
        this.moldId = moldId;
    }

    public Integer getIsCompatible() {
        return isCompatible;
    }

    public void setIsCompatible(Integer isCompatible) {
        this.isCompatible = isCompatible;
    }
}
