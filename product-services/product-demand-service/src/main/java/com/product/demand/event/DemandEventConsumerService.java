package com.product.demand.event;

import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.product.cloud.messaging.api.EventEnvelope;
import com.product.cloud.messaging.api.EventTypes;
import com.product.cloud.messaging.codec.EnvelopeCodec;
import com.product.cloud.messaging.consume.ConsumedEvent;
import com.product.cloud.messaging.consume.ConsumedEventRecorder;
import com.product.cloud.messaging.outbox.OutboxPublisher;
import com.product.demand.common.exception.ServiceException;
import com.product.demand.domain.entity.CustomerOrder;
import com.product.demand.domain.entity.OrderLine;
import com.product.demand.domain.entity.PlanningBatchState;
import com.product.demand.service.DemandDataVersionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 需求域事件消费/状态推进服务（Phase 5，ADR-0004）。
 *
 * <p>batch.progress.changed（Planning → Demand）的落域行为与单体进程内刷新链
 * 逐字段对齐：</p>
 * <ol>
 *   <li>批次状态写入本域投影 planning_batch_state（事件携带状态迁移，聚合输入本地化）；</li>
 *   <li>按投影重算订单行状态（单体 OrderLineStatusRefresher 冻结聚合规则：该行全部
 *       批次状态），变更则落库 + bump 版本 + 经 Outbox 发布 order_line.progress.changed
 *       （出站记录，预留）；</li>
 *   <li>订单行状态变更后重算客户订单状态（单体 CustomerOrderStatusRefresher 冻结规则），
 *       变更则落库（无下游事件）。</li>
 * </ol>
 *
 * <p>幂等/乱序（ADR-0004 §4）：eventId 去重 + 批次聚合 occurredAt 单调性守卫
 * （过期批次事件记录后丢弃，投影保留较新状态）。</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "product.messaging", name = "enabled", havingValue = "true")
public class DemandEventConsumerService {

    /** 本服务消费组（= Nacos 注册名）。 */
    public static final String CONSUMER_GROUP = "product-demand";

    private final ConsumedEventRecorder consumedEventRecorder;
    private final OutboxPublisher outboxPublisher;
    private final DemandDataVersionService demandDataVersionService;

    public DemandEventConsumerService(ConsumedEventRecorder consumedEventRecorder,
                                      OutboxPublisher outboxPublisher,
                                      DemandDataVersionService demandDataVersionService) {
        this.consumedEventRecorder = consumedEventRecorder;
        this.outboxPublisher = outboxPublisher;
        this.demandDataVersionService = demandDataVersionService;
    }

    /**
     * 消费 batch.progress.changed（同一事务：去重/守卫/投影/订单行/订单/发件箱/流水）。
     * 返回消费流水 outcome。
     */
    @Transactional(rollbackFor = Exception.class)
    public String consumeBatchProgressChanged(EventEnvelope envelope) {
        requireBatchProgressChanged(envelope);
        if (consumedEventRecorder.alreadyConsumed(CONSUMER_GROUP, envelope.getEventId())) {
            log.info("重复事件跳过（幂等）: eventId={}", envelope.getEventId());
            return ConsumedEvent.OUTCOME_APPLIED;
        }
        Map<String, Object> payload = envelope.getPayload();
        String batchAggregate = envelope.getAggregateId();
        OffsetDateTime occurred = EnvelopeCodec.parseOccurredAt(envelope.getOccurredAt());
        OffsetDateTime lastApplied = consumedEventRecorder.lastAppliedAt(CONSUMER_GROUP, batchAggregate);
        if (lastApplied != null && occurred.isBefore(lastApplied)) {
            // ADR-0004 §4：过期事件记录后丢弃，投影保留较新状态（不回滚已提交状态）
            consumedEventRecorder.record(CONSUMER_GROUP, envelope, ConsumedEvent.OUTCOME_STALE);
            log.warn("过期批次事件丢弃: batchId={} eventId={} occurredAt={} lastApplied={}",
                    batchAggregate, envelope.getEventId(), envelope.getOccurredAt(), lastApplied);
            return ConsumedEvent.OUTCOME_STALE;
        }
        String outcome = applyBatchProgress(envelope, payload);
        consumedEventRecorder.record(CONSUMER_GROUP, envelope, outcome);
        return outcome;
    }

    /** 应用批次进度事件（供消费与对账修复共用）。 */
    public String applyBatchProgress(EventEnvelope envelope, Map<String, Object> payload) {
        Long batchId = requireLong(payload, "batchId");
        Long orderLineId = optionalLong(payload, "orderLineId");
        String batchStatus = requireString(payload, "batchStatus");

        upsertProjection(batchId, orderLineId, batchStatus, envelope);
        if (orderLineId == null) {
            log.warn("批次事件缺少订单行ID，仅更新投影: batchId={} eventId={}", batchId, envelope.getEventId());
            return ConsumedEvent.OUTCOME_UNKNOWN;
        }
        refreshOrderLine(orderLineId, envelope.getCorrelationId());
        return ConsumedEvent.OUTCOME_APPLIED;
    }

    /** 投影 upsert（同聚合单调性已由 occurredAt 守卫保证）。 */
    private void upsertProjection(Long batchId, Long orderLineId, String batchStatus, EventEnvelope envelope) {
        PlanningBatchState state = Db.lambdaQuery(PlanningBatchState.class)
                .eq(PlanningBatchState::getBatchId, batchId)
                .one();
        OffsetDateTime occurred = EnvelopeCodec.parseOccurredAt(envelope.getOccurredAt());
        if (state == null) {
            state = new PlanningBatchState();
            state.setBatchId(batchId);
            state.setOrderLineId(orderLineId);
            state.setStatus(batchStatus);
            state.setLastEventId(envelope.getEventId());
            state.setOccurredAt(occurred.toLocalDateTime());
            state.setUpdatedAt(java.time.LocalDateTime.now());
            Db.save(state);
            return;
        }
        if (orderLineId != null) {
            state.setOrderLineId(orderLineId);
        }
        state.setStatus(batchStatus);
        state.setLastEventId(envelope.getEventId());
        state.setOccurredAt(occurred.toLocalDateTime());
        state.setUpdatedAt(java.time.LocalDateTime.now());
        Db.updateById(state);
    }

    /**
     * 重算订单行状态（冻结聚合规则）并在变更时落库 + bump + 出站
     * order_line.progress.changed；随后重算客户订单状态（冻结规则）。
     * 供事件消费、对账修复与人工修正端点共用。
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean refreshOrderLine(Long orderLineId, String correlationId) {
        OrderLine orderLine = Db.lambdaQuery(OrderLine.class)
                .eq(OrderLine::getOrderLineId, orderLineId)
                .one();
        if (orderLine == null) {
            log.warn("订单行不存在，跳过重算: orderLineId={}", orderLineId);
            return false;
        }
        List<String> batchStatuses = Db.lambdaQuery(PlanningBatchState.class)
                .select(PlanningBatchState::getStatus)
                .eq(PlanningBatchState::getOrderLineId, orderLineId)
                .list()
                .stream()
                .map(PlanningBatchState::getStatus)
                .filter(Objects::nonNull)
                .toList();
        if (batchStatuses.isEmpty()) {
            return false;
        }
        String targetLineStatus = DemandStatusResolvers.resolveOrderLineStatus(batchStatuses, orderLine.getStatus());
        boolean lineChanged = !Objects.equals(targetLineStatus, orderLine.getStatus());
        if (lineChanged) {
            Db.lambdaUpdate(OrderLine.class)
                    .set(OrderLine::getStatus, targetLineStatus)
                    .eq(OrderLine::getOrderLineId, orderLineId)
                    .update();
            demandDataVersionService.bump();
            EventEnvelope lineEvent = new EventEnvelope();
            lineEvent.setEventType(EventTypes.ORDER_LINE_PROGRESS_CHANGED);
            lineEvent.setAggregateId(String.valueOf(orderLineId));
            lineEvent.setCorrelationId(correlationId == null || correlationId.isBlank()
                    ? java.util.UUID.randomUUID().toString() : correlationId);
            lineEvent.setOccurredAt(EnvelopeCodec.now());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("orderLineId", orderLineId);
            payload.put("orderId", orderLine.getOrderId());
            payload.put("lineStatus", targetLineStatus);
            lineEvent.setPayload(payload);
            outboxPublisher.append(lineEvent);
            log.info("订单行状态推进: orderLineId={} {}->{} correlationId={}",
                    orderLineId, orderLine.getStatus(), targetLineStatus, lineEvent.getCorrelationId());
        }
        // 订单行状态变更后重算客户订单（单体刷新链的订单级联动；无下游事件）
        if (orderLine.getOrderId() != null) {
            refreshCustomerOrder(orderLine.getOrderId());
        }
        return lineChanged;
    }

    /** 重算客户订单状态（冻结聚合规则，变更才落库；与单体 CustomerOrderStatusRefresher 一致）。 */
    private void refreshCustomerOrder(Long orderId) {
        CustomerOrder order = Db.lambdaQuery(CustomerOrder.class)
                .select(CustomerOrder::getOrderId, CustomerOrder::getStatus)
                .eq(CustomerOrder::getOrderId, orderId)
                .one();
        if (order == null) {
            return;
        }
        List<String> lineStatuses = Db.lambdaQuery(OrderLine.class)
                .select(OrderLine::getStatus)
                .eq(OrderLine::getOrderId, orderId)
                .list()
                .stream()
                .map(OrderLine::getStatus)
                .filter(Objects::nonNull)
                .toList();
        if (lineStatuses.isEmpty()) {
            return;
        }
        String targetOrderStatus = DemandStatusResolvers.resolveCustomerOrderStatus(lineStatuses, order.getStatus());
        if (!Objects.equals(targetOrderStatus, order.getStatus())) {
            Db.lambdaUpdate(CustomerOrder.class)
                    .set(CustomerOrder::getStatus, targetOrderStatus)
                    .eq(CustomerOrder::getOrderId, orderId)
                    .update();
            demandDataVersionService.bump();
            log.info("订单状态推进: orderId={} {}->{}", orderId, order.getStatus(), targetOrderStatus);
        }
    }

    private void requireBatchProgressChanged(EventEnvelope envelope) {
        if (!EventTypes.BATCH_PROGRESS_CHANGED.equals(envelope.getEventType())) {
            throw new ServiceException("事件类型不符，期望 batch.progress.changed: " + envelope.getEventType());
        }
        requireLong(envelope.getPayload(), "batchId");
        requireString(envelope.getPayload(), "batchStatus");
    }

    static Long requireLong(Map<String, Object> payload, String field) {
        Long value = optionalLong(payload, field);
        if (value == null) {
            throw new ServiceException("事件 payload 缺少合法字段: " + field);
        }
        return value;
    }

    static Long optionalLong(Map<String, Object> payload, String field) {
        Object value = payload == null ? null : payload.get(field);
        return value instanceof Number number ? number.longValue() : null;
    }

    static String requireString(Map<String, Object> payload, String field) {
        Object value = payload == null ? null : payload.get(field);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new ServiceException("事件 payload 缺少合法字段: " + field);
        }
        return text;
    }
}
