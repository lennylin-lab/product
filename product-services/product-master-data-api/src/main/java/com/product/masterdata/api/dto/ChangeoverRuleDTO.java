package com.product.masterdata.api.dto;

import java.io.Serializable;

/**
 * 换型规则契约条目（Phase 4）。
 *
 * <p>单体排程取 changeover_rule 表 {@code limit 1} 的默认规则；字段语义一致
 * （sameMold/differentMold 基础换型分钟 + material/color 附加分钟）。</p>
 */
public class ChangeoverRuleDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long ruleId;

    private Integer sameMoldTimeMin;

    private Integer differentMoldTimeMin;

    private Integer materialChangeExtraMin;

    private Integer colorChangeExtraMin;

    public Long getRuleId() {
        return ruleId;
    }

    public void setRuleId(Long ruleId) {
        this.ruleId = ruleId;
    }

    public Integer getSameMoldTimeMin() {
        return sameMoldTimeMin;
    }

    public void setSameMoldTimeMin(Integer sameMoldTimeMin) {
        this.sameMoldTimeMin = sameMoldTimeMin;
    }

    public Integer getDifferentMoldTimeMin() {
        return differentMoldTimeMin;
    }

    public void setDifferentMoldTimeMin(Integer differentMoldTimeMin) {
        this.differentMoldTimeMin = differentMoldTimeMin;
    }

    public Integer getMaterialChangeExtraMin() {
        return materialChangeExtraMin;
    }

    public void setMaterialChangeExtraMin(Integer materialChangeExtraMin) {
        this.materialChangeExtraMin = materialChangeExtraMin;
    }

    public Integer getColorChangeExtraMin() {
        return colorChangeExtraMin;
    }

    public void setColorChangeExtraMin(Integer colorChangeExtraMin) {
        this.colorChangeExtraMin = colorChangeExtraMin;
    }
}
