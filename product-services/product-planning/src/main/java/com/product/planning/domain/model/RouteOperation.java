package com.product.planning.domain.model;

import lombok.Data;

/**
 * 路线工序排程视图（Phase 4）：与单体 RouteOperation 实体同构的纯内存模型
 * （product_route/route_operation 归 product-master-data，经 getProducts 契约
 * activeRoute.operations 加载映射；字段仅覆盖任务生成/校验消费面）。
 */
@Data
public class RouteOperation {

    private Long opId;

    private Long routeId;

    /** 工序编码（SETUP/INJECT/POST_QC_PUTAWAY）。 */
    private String opCode;

    /** 工序顺序。 */
    private Integer sequence;

    /** 可用资源规则引用。 */
    private String eligibleResourceRule;

    /** 标准工时模型引用。 */
    private String stdTimeModel;

    /** 排队策略（FIFO/SAME_MOLD_FIRST/EDD）。 */
    private String queuePolicy;
}
