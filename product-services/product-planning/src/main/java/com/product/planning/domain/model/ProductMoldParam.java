package com.product.planning.domain.model;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 产品模具参数排程视图（Phase 4）：与单体 ProductMoldParam 实体同构的纯内存模型
 * （product_mold_param 归 product-master-data，经 getProducts 契约 moldParams 加载映射；
 * A2 产能公式输入，校验语义见 ProductMoldParamValidator）。
 */
@Data
public class ProductMoldParam {

    private Long productId;

    private Long moldId;

    /** 周期时间（秒/模次）。 */
    private BigDecimal cycleTimeSec;

    /** 模穴数。 */
    private Integer cavity;

    /** 良率（0-1；null/<=0 视为 1）。 */
    private BigDecimal yieldRate;

    /**稼动率（0-1；null/<=0 视为 1）。 */
    private BigDecimal utilization;
}
