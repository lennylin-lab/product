package com.product.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.product.common.annotation.BizIdPrefix;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import com.product.common.core.entity.BaseEntity;

/**
 * 换型规则对象 changeover_rule
 *
 * @author product
 * @date 2026-04-25
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("changeover_rule")
@BizIdPrefix("CR")
public class ChangeoverRule extends BaseEntity {
    private static final long serialVersionUID = 1L;

    /** 规则ID */
    @TableId(value = "rule_id", type = IdType.ASSIGN_UUID)
    private String ruleId;

    /** 同模切换时间 */
    @TableField("same_mold_time_min")
    private Integer sameMoldTimeMin;

    /** 换模时间 */
    @TableField("different_mold_time_min")
    private Integer differentMoldTimeMin;

    /** 换料附加时间 */
    @TableField("material_change_extra_min")
    private Integer materialChangeExtraMin;

    /** 换色附加时间 */
    @TableField("color_change_extra_min")
    private Integer colorChangeExtraMin;
}
