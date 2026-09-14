package com.product.masterdata.api.dto;

import java.io.Serializable;

/**
 * 机台-模具兼容性契约条目（Phase 4 新增）。
 *
 * <p>排程级联选择（chooseMold/machineSupportsMold）消费机台兼容模具列表；
 * Phase 3 的 resources/batch 契约未携带该数据，本次增量补充（additive 契约变更，
 * 不影响既有消费方）。字段与 machine_mold_compatibility 表一致。</p>
 */
public class MachineMoldCompatibilityDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long machineId;

    private Long moldId;

    /** 是否兼容（1 兼容；null 视为兼容，与单体算法判断一致）。 */
    private Integer isCompatible;

    public Long getMachineId() {
        return machineId;
    }

    public void setMachineId(Long machineId) {
        this.machineId = machineId;
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
