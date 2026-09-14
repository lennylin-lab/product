package com.product.cloud.messaging.replay;

import com.product.cloud.messaging.api.EventEnvelope;
import com.product.cloud.messaging.audit.OpsAuditService;
import com.product.cloud.messaging.codec.EnvelopeCodec;
import com.product.cloud.messaging.config.MessagingProperties;
import com.product.cloud.messaging.deadletter.DeadLetterAudit;
import com.product.cloud.messaging.deadletter.DeadLetterAuditor;
import com.product.cloud.messaging.outbox.OutboxDao;
import com.product.cloud.messaging.outbox.OutboxMessage;
import com.product.cloud.messaging.outbox.OutboxRelay;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 人工重放工具（ADR-0004 §6）：
 *
 * <ul>
 *   <li>outbox 重放：从本服务 outbox 取原始事件（按 eventId/聚合/类型/状态筛选），
 *       以<b>新 eventId + 原 correlationId + 原 payload + 原 occurredAt</b> 追加新
 *       PENDING 行（replayed_from 记录来源），由 OutboxRelay 正常投递；
 *       过期事件会被消费方单调性守卫丢弃（无害）。</li>
 *   <li>死信重放：从本服务 dead_letter_audit 取原始 envelope，直接重投原 exchange
 *       （routing key = eventType），标记审计行已重放。</li>
 * </ul>
 *
 * <p>全部动作写 ops_audit（REPLAY_OUTBOX / REPLAY_DLQ）留痕。</p>
 */
@Slf4j
public class EventReplayService {

    private final OutboxDao outboxDao;
    private final DeadLetterAuditor deadLetterAuditor;
    private final OpsAuditService opsAuditService;
    private final RabbitTemplate rabbitTemplate;
    private final MessagingProperties properties;

    public EventReplayService(OutboxDao outboxDao, DeadLetterAuditor deadLetterAuditor,
                              OpsAuditService opsAuditService, RabbitTemplate rabbitTemplate,
                              MessagingProperties properties) {
        this.outboxDao = outboxDao;
        this.deadLetterAuditor = deadLetterAuditor;
        this.opsAuditService = opsAuditService;
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
    }

    /** 按 eventId/聚合/类型/状态筛选 outbox 行（人工核对用）。 */
    public List<OutboxMessage> findOutbox(String eventId, String aggregateId, String eventType,
                                          String status, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM event_outbox WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (notBlank(eventId)) {
            sql.append(" AND event_id = ?");
            args.add(eventId);
        }
        if (notBlank(aggregateId)) {
            sql.append(" AND aggregate_id = ?");
            args.add(aggregateId);
        }
        if (notBlank(eventType)) {
            sql.append(" AND event_type = ?");
            args.add(eventType);
        }
        if (notBlank(status)) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        sql.append(" ORDER BY id ASC LIMIT ").append(Math.max(1, Math.min(limit, 500)));
        return outboxDao.query(sql.toString(), args.toArray());
    }

    /** 重放一条 outbox 事件（新 eventId、原 correlationId/payload/occurredAt）。返回新 eventId。 */
    @Transactional
    public String replayOutbox(long outboxId, String operator) {
        OutboxMessage source = outboxDao.findById(outboxId);
        if (source == null) {
            throw new IllegalArgumentException("outbox 行不存在: " + outboxId);
        }
        EventEnvelope envelope = OutboxRelay.toEnvelope(source);
        OutboxMessage replay = new OutboxMessage();
        replay.setEventId(UUID.randomUUID().toString());
        replay.setEventType(source.getEventType());
        replay.setAggregateId(source.getAggregateId());
        replay.setCorrelationId(source.getCorrelationId());
        replay.setProducer(source.getProducer());
        replay.setPayload(source.getPayload());
        replay.setReplayedFrom(source.getEventId());
        replay.setOccurredAt(source.getOccurredAt());
        outboxDao.insert(replay);
        String detail = "outboxId=" + outboxId + " srcEventId=" + source.getEventId()
                + " -> newEventId=" + replay.getEventId() + " type=" + source.getEventType()
                + " aggregate=" + source.getAggregateId();
        opsAuditService.record("REPLAY_OUTBOX", operator,
                "{\"outboxId\":" + outboxId + "}", "OK", detail);
        log.info("outbox 重放已入队: {}", detail);
        return replay.getEventId();
    }

    /** 重放一条死信审计行（直接重投原 exchange/routing key）。返回新 eventId。 */
    @Transactional
    public String replayDeadLetter(long auditId, String operator) {
        DeadLetterAudit audit = deadLetterAuditor.findById(auditId);
        if (audit == null) {
            throw new IllegalArgumentException("死信审计行不存在: " + auditId);
        }
        if (Boolean.TRUE.equals(audit.getReplayed())) {
            throw new IllegalStateException("死信已重放: auditId=" + auditId
                    + " replayEventId=" + audit.getReplayEventId());
        }
        EventEnvelope envelope = EnvelopeCodec.parse(
                audit.getPayload() == null ? new byte[0] : audit.getPayload().getBytes());
        envelope.setEventId(UUID.randomUUID().toString());
        // correlationId/occurredAt 保持原值（迟到事件由消费方单调性守卫判定）
        String exchange = properties.getDeadLetterReplayExchange();
        if (!notBlank(exchange)) {
            throw new IllegalStateException("product.messaging.dead-letter-replay-exchange 未配置");
        }
        org.springframework.amqp.rabbit.connection.CorrelationData correlation =
                new org.springframework.amqp.rabbit.connection.CorrelationData(envelope.getEventId());
        // 直接构造 AMQP Message（原始 JSON 字节，同 OutboxRelay——避免 Jackson byte[]->Base64 双重编码）
        org.springframework.amqp.core.MessageProperties props =
                new org.springframework.amqp.core.MessageProperties();
        props.setContentType("application/json");
        props.setContentEncoding("UTF-8");
        props.setDeliveryMode(org.springframework.amqp.core.MessageDeliveryMode.PERSISTENT);
        props.setHeader("eventId", envelope.getEventId());
        props.setHeader("eventType", envelope.getEventType());
        rabbitTemplate.send(exchange, envelope.getEventType(),
                new org.springframework.amqp.core.Message(EnvelopeCodec.encode(envelope), props), correlation);
        String replayEventId = envelope.getEventId();
        deadLetterAuditor.markReplayed(auditId, replayEventId);
        opsAuditService.record("REPLAY_DLQ", operator,
                "{\"auditId\":" + auditId + "}", "OK",
                "auditId=" + auditId + " srcEventId=" + audit.getEventId()
                        + " -> newEventId=" + replayEventId + " type=" + audit.getEventType()
                        + " exchange=" + exchange);
        log.info("死信重放完成: auditId={} newEventId={}", auditId, replayEventId);
        return replayEventId;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
