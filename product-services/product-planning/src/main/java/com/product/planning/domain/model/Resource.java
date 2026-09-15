package com.product.planning.domain.model;

import lombok.Data;

import java.util.List;

/**
 * 资源排程视图（Phase 4）：与单体 Resource 实体同构的纯内存模型。
 *
 * <p>resource/machine/mold/resource_capability 归 product-master-data 所有（ADR-0005），
 * 本服务禁止共享持久化模型；排程快照经 MasterDataBatchQueryApi.resources/batch 加载
 * ResourceDTO 后映射为本模型，字段仅覆盖排程算法消费面（与单体同名列语义一致）。</p>
 */
@Data
public class Resource {

    private Long resourceId;

    private String resourceType;

    private String name;

    /** 状态（AVAILABLE/BUSY/DOWN/MAINTENANCE/OFFSHIFT）。 */
    private String status;

    private Long calendarId;

    /** 机台扩展（resourceType=MACHINE 时存在）。 */
    private Machine machine;

    /** 夹具扩展（2026-09-15 增量；resourceType=FIXTURE 时存在，非夹具资源为 null）。 */
    private Fixture fixture;

    /** 能力矩阵（排程 opCode×productId 校验）。 */
    private List<ResourceCapability> capabilityList;
}
