package com.product.pps.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 任务排程优先级上下文。
 *
 * 说明：用于交期优先策略下的任务排序，不污染 operation_task 实体。
 */
@Data
public class TaskSchedulingPriorityDTO {
    /** 工序任务ID */
    private Long taskId;
    /** 关联的生产批次ID */
    private Long batchId;
    /** 订单交期（从 CustomerOrder 关联获取） */
    private LocalDateTime dueDate;
    /** 订单优先级（数值越大越紧急） */
    private Long priority;
}
