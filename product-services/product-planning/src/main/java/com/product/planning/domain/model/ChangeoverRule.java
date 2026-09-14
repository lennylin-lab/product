package com.product.planning.domain.model;

import lombok.Data;

/**
 * 换型规则排程视图（Phase 4）：与单体 ChangeoverRule 实体同构的纯内存模型
 * （changeover_rule 归 product-master-data，经 getCurrentChangeoverRule 契约加载）。
 */
@Data
public class ChangeoverRule {

    private Long ruleId;

    /** 同模具换型时间(分钟)。 */
    private Integer sameMoldTimeMin;

    /** 不同模具换型时间(分钟)。 */
    private Integer differentMoldTimeMin;

    /** 换料附加时间(分钟)。 */
    private Integer materialChangeExtraMin;

    /** 换色附加时间(分钟)。 */
    private Integer colorChangeExtraMin;
}
