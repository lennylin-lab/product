package com.product.planning.event;

import com.product.cloud.messaging.api.EventEnvelope;
import com.product.cloud.messaging.api.EventTypes;
import com.product.cloud.messaging.consume.ConsumedEvent;
import com.product.cloud.messaging.consume.ConsumedEventRecorder;
import com.product.cloud.messaging.outbox.OutboxPublisher;
import com.product.planning.common.exception.ServiceException;
import com.product.planning.domain.entity.OperationTask;
import com.product.planning.domain.entity.ProductionBatch;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.baomidou.mybatisplus.extension.toolkit.Db;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 计划域事件消费/状态推进服务（Phase 5，ADR-0004）。
 *
 * <p>task.status.changed（Execution → Planning）的落域行为与单体进程内刷新链
 * 逐字段对齐：</p>
 * <ol>
 *   <li>任务状态无条件映射落库（单体 TaskEventServiceImpl.updateTaskStatus 冻结语义：
 *       START/RESUME→RUNNING、PAUSE→PAUSED、FINISH→DONE，无前置状态守卫）；</li>
 *   <li>按任务所属批次重算批次状态（单体 ProductionBatchStatusRefresher 冻结聚合规则），
 *       变更则落库并经 Outbox 发布 batch.progress.changed（Planning → Demand），
 *       correlationId 透传上游事件；</li>
 *   <li>任务无所属批次 → 仅落任务状态（单体 loadBatchIdByTaskId null → 不刷新，
 *       命令仍成功）。</li>
 * </ol>
 *
 * <p>幂等/乱序（ADR-0004 §4）：eventId 去重（consumed_event）+ 同聚合 occurredAt
 * 单调性守卫——过期事件记录后丢弃（不回滚已提交状态）。</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "product.messaging", name = "enabled", havingValue = "true")
public class PlanningEventConsumerService {

    /** 本服务消费组（= Nacos 注册名）。 */
    public static final String CONSUMER_GROUP = "product-planning";

    private final ConsumedEventRecorder consumedEventRecorder;
    private final OutboxPublisher outboxPublisher;

    public PlanningEventConsumerService(ConsumedEventRecorder consumedEventRecorder,
                                        OutboxPublisher outboxPublisher) {
        this.consumedEventRecorder = consumedEventRecorder;
        this.outboxPublisher = outboxPublisher;
    }

    /**
     * 消费 task.status.changed（同一事务：去重/守卫/落库/发件箱/流水）。
     * 返回消费流水 outcome。
     */
    @Transactional(rollbackFor = Exception.class)
    public String consumeTaskStatusChanged(EventEnvelope envelope) {
        requireTaskStatusChanged(envelope);
        if (consumedEventRecorder.alreadyConsumed(CONSUMER_GROUP, envelope.getEventId())) {
            log.info("重复事件跳过（幂等）: eventId={}", envelope.getEventId());
            return ConsumedEvent.OUTCOME_APPLIED;
        }
        OffsetDateTime occurred = com.product.cloud.messaging.codec.EnvelopeCodec
                .parseOccurredAt(envelope.getOccurredAt());
        OffsetDateTime lastApplied = consumedEventRecorder.lastAppliedAt(
                CONSUMER_GROUP, envelope.getAggregateId());
        if (lastApplied != null && occurred.isBefore(lastApplied)) {
            // ADR-0004 §4：过期事件记录后丢弃，不回滚已提交状态
            consumedEventRecorder.record(CONSUMER_GROUP, envelope, ConsumedEvent.OUTCOME_STALE);
            log.warn("过期事件丢弃: aggregate={} eventId={} occurredAt={} lastApplied={}",
                    envelope.getAggregateId(), envelope.getEventId(), envelope.getOccurredAt(), lastApplied);
            return ConsumedEvent.OUTCOME_STALE;
        }
        String outcome = applyTaskStatusChanged(envelope);
        consumedEventRecorder.record(CONSUMER_GROUP, envelope, outcome);
        return outcome;
    }

    /**
     * 消费 resource.status.changed（仅记录/告警用途，ADR-0004 §2；不驱动本域状态机）。
     */
    @Transactional(rollbackFor = Exception.class)
    public String consumeResourceStatusChanged(EventEnvelope envelope) {
        if (!EventTypes.RESOURCE_STATUS_CHANGED.equals(envelope.getEventType())) {
            throw new ServiceException("事件类型不符: " + envelope.getEventType());
        }
        if (consumedEventRecorder.alreadyConsumed(CONSUMER_GROUP, envelope.getEventId())) {
            log.info("重复资源事件跳过（幂等）: eventId={}", envelope.getEventId());
            return ConsumedEvent.OUTCOME_APPLIED;
        }
        log.info("资源状态事件记录（记录/告警用途）: resourceId={} from={} to={} eventId={}",
                envelope.getPayload().get("resourceId"), envelope.getPayload().get("fromStatus"),
                envelope.getPayload().get("toStatus"), envelope.getEventId());
        consumedEventRecorder.record(CONSUMER_GROUP, envelope, ConsumedEvent.OUTCOME_APPLIED);
        return ConsumedEvent.OUTCOME_APPLIED;
    }

    /**
     * 应用任务状态事件（供消费与对账修复共用）。返回消费流水 outcome。
     */
    public String applyTaskStatusChanged(EventEnvelope envelope) {
        Map<String, Object> payload = envelope.getPayload();
        Long taskId = requireLong(payload, "taskId");
        String targetStatus = requireString(payload, "targetStatus");
        OperationTask task = Db.lambdaQuery(OperationTask.class)
                .eq(OperationTask::getTaskId, taskId)
                .one();
        if (task == null) {
            log.warn("任务不存在，事件无操作（与单体[任务不存在时刷新链为空操作]等价）: taskId={} eventId={}",
                    taskId, envelope.getEventId());
            return ConsumedEvent.OUTCOME_UNKNOWN;
        }
        // 无条件映射（单体冻结语义）
        Db.lambdaUpdate(OperationTask.class)
                .set(OperationTask::getStatus, targetStatus)
                .eq(OperationTask::getTaskId, taskId)
                .update();
        Long batchId = task.getBatchId();
        if (batchId == null) {
            return ConsumedEvent.OUTCOME_APPLIED;
        }
        recomputeBatchStatus(batchId, envelope.getCorrelationId());
        return ConsumedEvent.OUTCOME_APPLIED;
    }

    /**
     * 重算批次状态（冻结聚合规则）并在变更时发布 batch.progress.changed。
     * 供事件消费、对账修复（RECON_HEAL）与人工修正端点共用。
     *
     * @return 状态是否发生变更
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean recomputeBatchStatus(Long batchId, String correlationId) {
        ProductionBatch batch = Db.lambdaQuery(ProductionBatch.class)
                .eq(ProductionBatch::getBatchId, batchId)
                .one();
        if (batch == null) {
            log.warn("批次不存在，跳过重算: batchId={}", batchId);
            return false;
        }
        List<String> taskStatuses = Db.lambdaQuery(OperationTask.class)
                .select(OperationTask::getStatus)
                .eq(OperationTask::getBatchId, batchId)
                .list()
                .stream()
                .map(OperationTask::getStatus)
                .filter(Objects::nonNull)
                .toList();
        if (taskStatuses.isEmpty()) {
            return false;
        }
        String targetStatus = BatchStatusResolver.resolveBatchStatus(taskStatuses, batch.getStatus());
        if (!BatchStatusResolver.differs(targetStatus, batch.getStatus())) {
            return false;
        }
        Db.lambdaUpdate(ProductionBatch.class)
                .set(ProductionBatch::getStatus, targetStatus)
                .eq(ProductionBatch::getBatchId, batchId)
                .update();
        EventEnvelope changed = new EventEnvelope();
        changed.setEventType(EventTypes.BATCH_PROGRESS_CHANGED);
        changed.setAggregateId(String.valueOf(batchId));
        changed.setCorrelationId(correlationId == null || correlationId.isBlank()
                ? java.util.UUID.randomUUID().toString() : correlationId);
        changed.setOccurredAt(com.product.cloud.messaging.codec.EnvelopeCodec.now());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("batchId", batchId);
        payload.put("orderLineId", batch.getOrderLineId());
        payload.put("batchStatus", targetStatus);
        changed.setPayload(payload);
        outboxPublisher.append(changed);
        log.info("批次状态推进并发布 batch.progress.changed: batchId={} {}->{} correlationId={}",
                batchId, batch.getStatus(), targetStatus, changed.getCorrelationId());
        return true;
    }

    /** 校验信封为 task.status.changed 且 payload 结构合法（非法 → 异常 → 重试 → 死信）。 */
    private void requireTaskStatusChanged(EventEnvelope envelope) {
        if (!EventTypes.TASK_STATUS_CHANGED.equals(envelope.getEventType())) {
            throw new ServiceException("事件类型不符，期望 task.status.changed: " + envelope.getEventType());
        }
        requireLong(envelope.getPayload(), "taskId");
        requireString(envelope.getPayload(), "targetStatus");
    }

    static Long requireLong(Map<String, Object> payload, String field) {
        Object value = payload == null ? null : payload.get(field);
        if (!(value instanceof Number number)) {
            throw new ServiceException("事件 payload 缺少合法字段: " + field);
        }
        return number.longValue();
    }

    static String requireString(Map<String, Object> payload, String field) {
        Object value = payload == null ? null : payload.get(field);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new ServiceException("事件 payload 缺少合法字段: " + field);
        }
        return text;
    }
}
