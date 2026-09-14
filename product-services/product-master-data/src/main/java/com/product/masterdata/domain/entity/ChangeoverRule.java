package com.product.masterdata.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.product.masterdata.common.core.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 换型规则对象 changeover_rule（Phase 4 补齐实体：排程换型计算契约端点所需；
 * 表随 Phase 3 schema 已建，此前无消费方故未建实体）。
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("changeover_rule")
public class ChangeoverRule extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 规则ID（主键） */
    @TableId(value = "rule_id", type = IdType.ASSIGN_ID)
    private Long ruleId;

    /** 同模具换型时间(分钟) */
    private Integer sameMoldTimeMin;

    /** 不同模具换型时间(分钟) */
    private Integer differentMoldTimeMin;

    /** 换料附加时间(分钟) */
    private Integer materialChangeExtraMin;

    /** 换色附加时间(分钟) */
    private Integer colorChangeExtraMin;
}
