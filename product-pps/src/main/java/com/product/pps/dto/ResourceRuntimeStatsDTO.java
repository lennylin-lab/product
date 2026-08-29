package com.product.pps.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 多资源运行时聚合信息。
 *
 * <p>
 * 用于 ResourceRuntimeContext 预加载：按 (resourceType, resourceId) 维度
 * 统计 task_assignment_resource 表中活跃派工的最近结束时间和最大序号。
 * </p>
 */
@Data
public class ResourceRuntimeStatsDTO {
    /** 资源类型（MACHINE / MOLD / PERSON / WORKSTATION） */
    private String resourceType;
    /** 资源ID */
    private String resourceId;
    /** 该资源上最近任务的计划结束时间 */
    private LocalDateTime latestEndTime;
    /** 该资源上的最大序号 */
    private Long maxSequence;
}
