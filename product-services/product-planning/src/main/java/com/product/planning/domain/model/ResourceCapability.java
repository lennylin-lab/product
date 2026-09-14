package com.product.planning.domain.model;

import lombok.Data;

/**
 * 资源能力行（Phase 4）：与单体 ResourceCapability 实体同构的纯内存模型。
 */
@Data
public class ResourceCapability {

    private Long capabilityId;

    private Long resourceId;

    private String opCode;

    private Long productId;

    /** 是否启用（1 启用）。 */
    private Integer isEnabled;
}
