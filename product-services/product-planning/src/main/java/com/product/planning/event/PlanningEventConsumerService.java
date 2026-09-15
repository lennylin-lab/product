package com.product.planning.event;

import com.product.cloud.messaging.api.EventEnvelope;
import com.product.cloud.messaging.api.EventTypes;
import com.product.cloud.messaging.consume.ConsumedEvent;
import com.product.cloud.messaging.consume.ConsumedEventRecorder;
import com.product.cloud.messaging.outbox.OutboxPublisher;
import com.product.masterdata.api.ResourceStatusUpdateApi;
import com.product.masterdata.api.dto.ResourceStatusUpdateRequest;
import com.product.masterdata.api.dto.ResourceStatusUpdateResponse;
import com.product.planning.common.exception.ServiceException;
import com.product.planning.domain.entity.OperationTask;
import com.product.planning.domain.entity.ProductionBatch;
import com.product.planning.service.impl.RescheduleTriggerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final ResourceStatusUpdateApi resourceStatusUpdateApi;
    private final RescheduleTriggerService rescheduleTriggerService;

    public PlanningEventConsumerService(ConsumedEventRecorder consumedEventRecorder,
                                        OutboxPublisher outboxPublisher,
                                        ResourceStatusUpdateApi resourceStatusUpdateApi) {
        this(consumedEventRecorder, outboxPublisher, resourceStatusUpdateApi, null);
    }

    @Autowired
    public PlanningEventConsumerService(ConsumedEventRecorder consumedEventRecorder,
                                        OutboxPublisher outboxPublisher,
                                        ResourceStatusUpdateApi resourceStatusUpdateApi,
                                        RescheduleTriggerService rescheduleTriggerService) {
        this.consumedEventRecorder = consumedEventRecorder;
        this.outboxPublisher = outboxPublisher;
        this.resourceStatusUpdateApi = resourceStatusUpdateApi;
        this.rescheduleTriggerService = rescheduleTriggerService;
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
     * 消费 resource.status.changed（KD3 升级，2026-09-16：回写 master-data 权威资源状态，
     * 原仅记录/告警；KD2/R4 升级，2026-09-16：回写成功后按触发集合自动发起全量重排）。
     * 顺序契约：回写成功是后续任何触发的前置——回写（fail-closed）→ 触发裁决 → record(APPLIED)。
     *
     * <p>fail-closed：幂等检查在前（重复事件不重复回写）；回写 Feign 失败/超时/提供方拒绝
     * → 异常上抛<b>不 ack</b>（既有有界重试 → DLX → 死信审计；对账工具可重放），绝不吞错
     * 后照常 ack（丢事件 = 丢状态回写）。触发动作绝不使消费失败（标记即持久化，
     * 见 {@link #requestRescheduleIfTriggered}）。</p>
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
        writeBackResourceStatus(envelope);
        requestRescheduleIfTriggered(envelope);
        consumedEventRecorder.record(CONSUMER_GROUP, envelope, ConsumedEvent.OUTCOME_APPLIED);
        return ConsumedEvent.OUTCOME_APPLIED;
    }

    /**
     * 重排触发裁决（KD2/R4，09-16-reschedule-trigger）：回写成功后、record(APPLIED) 前；
     * 仅 {@code toStatus ∈ {DOWN, AVAILABLE}} 触发（DOWN 故障排除、AVAILABLE 恢复回归），
     * MAINTENANCE/OFFSHIFT/BUSY 只回写不触发。EXCEPTION/task.status.changed 消费路径不进本方法
     * （无容量变化，零改动）。触发动作绝不让消费失败进入重试/DLX：
     * {@link RescheduleTriggerService#requestReschedule} 内部分级吞错（pending 标记即持久化），
     * 此处再兜底捕获意外异常（仅记录，照常 record(APPLIED)）。
     */
    private void requestRescheduleIfTriggered(EventEnvelope envelope) {
        if (rescheduleTriggerService == null) {
            return;
        }
        String toStatus = requireString(envelope.getPayload(), "toStatus");
        if (!RescheduleTriggerService.RESCHEDULE_TRIGGER_STATUSES.contains(toStatus)) {
            return;
        }
        try {
            rescheduleTriggerService.requestReschedule();
            log.info("资源状态事件进入重排触发集: toStatus={} eventId={}", toStatus, envelope.getEventId());
        } catch (Exception ex) {
            log.error("重排触发异常（不影响事件 ack，pending 标记为持久化兜底）: eventId={}",
                    envelope.getEventId(), ex);
        }
    }

    /**
     * 回写资源权威状态（KD3）：经服务身份令牌（PlanningFeignAuthInterceptor，2s/3s、
     * NEVER_RETRY）调 master-data /internal/master-data/resource-status。master-data 校验
     * 资源存在与目标状态合法性；同状态重复回写提供方幂等静默成功（不重复 bump）。
     *
     * <p>fail-closed 双通道：Feign 异常直接上抛；提供方业务拒绝走错误契约（200 + 错误体
     * 反序列化后 resourceId 缺省）→ 响应校验不通过同样抛错——两条路都不 ack。</p>
     */
    private void writeBackResourceStatus(EventEnvelope envelope) {
        Map<String, Object> payload = envelope.getPayload();
        Long resourceId = requireLong(payload, "resourceId");
        String toStatus = requireString(payload, "toStatus");
        Object reasonCode = payload.get("reasonCode");
        ResourceStatusUpdateRequest request = new ResourceStatusUpdateRequest();
        request.setResourceId(resourceId);
        request.setToStatus(toStatus);
        request.setReasonCode(reasonCode == null ? null : String.valueOf(reasonCode));
        ResourceStatusUpdateResponse response = resourceStatusUpdateApi.updateResourceStatus(request);
        if (response == null || !resourceId.equals(response.getResourceId())
                || !toStatus.equals(response.getStatus())) {
            // 提供方拒绝（资源不存在/状态非法等错误契约体）→ 视为回写未生效，不 ack
            throw new ServiceException("资源状态回写响应非法（提供方拒绝或契约异常）: resourceId="
                    + resourceId + " toStatus=" + toStatus);
        }
        log.info("资源状态回写 master-data 成功: resourceId={} toStatus={} snapshotVersion={} eventId={}",
                resourceId, toStatus, response.getSnapshotVersion(), envelope.getEventId());
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
