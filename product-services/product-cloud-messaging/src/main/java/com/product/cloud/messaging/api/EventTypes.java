package com.product.cloud.messaging.api;

/**
 * 首批领域事件类型与 payload schema（ADR-0004 §2；routing key = eventType，ADR-0004 §5）。
 *
 * <p>payload schema（v1，只加不改——演进策略见 {@link EventEnvelope}）：</p>
 * <pre>
 * task.status.changed（Execution → Planning，taskId 聚合）:
 *   taskId        Long   任务 ID
 *   eventType     String 任务命令类型（START/PAUSE/RESUME/FINISH，与单体 task_event.event_type 同域）
 *   targetStatus  String 目标任务状态（RUNNING/PAUSED/RUNNING/DONE，冻结映射同单体）
 *   resourceId    Long?  事件关联资源（派工机台；单体 task_event.resource_id 同源，可为 null）
 *   occurredEventId Long 任务事件行 ID（task_event.event_id，追溯用）
 *
 * resource.status.changed（Execution → Planning，仅记录/告警用途，ADR-0004 §2）:
 *   resourceId    Long   资源 ID
 *   fromStatus    String? 原状态
 *   toStatus      String? 新状态
 *   reasonCode    String? 原因编码
 *   relatedTaskId Long?   关联任务 ID
 *   occurredEventId Long  resource_status_event 行 ID
 *
 * batch.progress.changed（Planning → Demand，batchId 聚合）:
 *   batchId       Long  批次 ID
 *   orderLineId   Long? 订单行 ID
 *   batchStatus   String 变更后的批次状态（PLANNED/RELEASED/IN_PROCESS/DONE）
 *
 * order_line.progress.changed（Demand 出站记录用，orderLineId 聚合；当前无消费方，预留）:
 *   orderLineId   Long  订单行 ID
 *   orderId       Long? 所属订单 ID
 *   lineStatus    String 变更后的订单行状态（NEW/RELEASED/IN_PRODUCTION/DONE）
 * </pre>
 */
public final class EventTypes {

    private EventTypes() {
    }

    /** 任务状态事件（Execution → Planning；触发任务/批次状态推进）。 */
    public static final String TASK_STATUS_CHANGED = "task.status.changed";

    /** 资源状态事件（Execution → Planning；仅记录/告警用途，不驱动状态机）。 */
    public static final String RESOURCE_STATUS_CHANGED = "resource.status.changed";

    /** 批次进度事件（Planning → Demand；触发订单行/订单状态推进）。 */
    public static final String BATCH_PROGRESS_CHANGED = "batch.progress.changed";

    /** 订单行进度事件（Demand 出站记录；当前无消费方，预留）。 */
    public static final String ORDER_LINE_PROGRESS_CHANGED = "order_line.progress.changed";
}
