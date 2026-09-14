package com.product.cloud.messaging.deadletter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 死信审计落库（ADR-0004 §4/§6）。
 *
 * <p>DLQ 审计监听器把死信（原始 envelope + x-death 失败原因）写入
 * {@code dead_letter_audit} 后 ack——DLQ 不留积压，死信事实以审计表为准；
 * 表清零即链路无死信。审计失败时监听器不 ack（容器 requeue），DLQ 保留消息，
 * 不丢死信。</p>
 */
@Slf4j
public class DeadLetterAuditor {

    private final JdbcTemplate jdbc;

    public DeadLetterAuditor(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 解析死信消息并落审计表（供各服务 DLQ @RabbitListener 调用）。返回审计行 ID。 */
    @Transactional
    public long audit(String consumerGroup, Message message) {
        DeadLetterAudit audit = new DeadLetterAudit();
        audit.setConsumerGroup(consumerGroup);
        audit.setSourceQueue(extractSourceQueue(message));
        audit.setFailureReason(extractFailureReason(message));

        String eventId = header(message, "eventId");
        String eventType = header(message, "eventType");
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        try {
            com.product.cloud.messaging.api.EventEnvelope envelope =
                    com.product.cloud.messaging.codec.EnvelopeCodec.parse(message.getBody());
            eventId = envelope.getEventId();
            eventType = envelope.getEventType();
            audit.setAggregateId(envelope.getAggregateId());
            audit.setCorrelationId(envelope.getCorrelationId());
            audit.setPayload(body);
        } catch (Exception parseFailure) {
            // 毒消息（非 JSON/结构非法）：原样留档，便于人工分析
            audit.setPayload(body);
            String reason = audit.getFailureReason();
            audit.setFailureReason((reason == null ? "" : reason + "; ") + "envelope解析失败: "
                    + parseFailure.getMessage());
        }
        audit.setEventId(eventId);
        audit.setEventType(eventType);

        jdbc.update("""
                        INSERT INTO dead_letter_audit
                            (consumer_group, source_queue, event_id, event_type, aggregate_id,
                             correlation_id, payload, failure_reason, replayed, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, NOW(3))
                        """,
                audit.getConsumerGroup(), audit.getSourceQueue(), audit.getEventId(),
                audit.getEventType(), audit.getAggregateId(), audit.getCorrelationId(),
                audit.getPayload(), audit.getFailureReason());
        Long id = jdbc.queryForObject(
                "SELECT id FROM dead_letter_audit WHERE consumer_group = ? AND event_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, consumerGroup, audit.getEventId());
        log.warn("死信已审计入库: group={} queue={} type={} eventId={} reason={}",
                consumerGroup, audit.getSourceQueue(), audit.getEventType(), audit.getEventId(),
                audit.getFailureReason());
        return id == null ? 0L : id;
    }

    /** 待人工处理的死信审计行。 */
    public List<DeadLetterAudit> findUnreplayed(String aggregateId, String eventType, int limit) {
        StringBuilder sql = new StringBuilder(
                "SELECT * FROM dead_letter_audit WHERE replayed = 0");
        java.util.List<Object> args = new java.util.ArrayList<>();
        if (aggregateId != null && !aggregateId.isBlank()) {
            sql.append(" AND aggregate_id = ?");
            args.add(aggregateId);
        }
        if (eventType != null && !eventType.isBlank()) {
            sql.append(" AND event_type = ?");
            args.add(eventType);
        }
        sql.append(" ORDER BY id ASC LIMIT ").append(Math.max(1, limit));
        return jdbc.query(sql.toString(), (rs, i) -> mapRow(rs), args.toArray());
    }

    public DeadLetterAudit findById(long id) {
        List<DeadLetterAudit> rows = jdbc.query(
                "SELECT * FROM dead_letter_audit WHERE id = ?", (rs, i) -> mapRow(rs), id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void markReplayed(long id, String replayEventId) {
        jdbc.update("UPDATE dead_letter_audit SET replayed = 1, replay_event_id = ?, replayed_at = NOW(3) WHERE id = ?",
                replayEventId, id);
    }

    public long countUnreplayed() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dead_letter_audit WHERE replayed = 0", Long.class);
        return count == null ? 0L : count;
    }

    @SuppressWarnings("unchecked")
    private static String extractSourceQueue(Message message) {
        Object death = firstDeath(message);
        if (death instanceof Map<?, ?> map && map.get("queue") != null) {
            return String.valueOf(map.get("queue"));
        }
        return null;
    }

    private static String extractFailureReason(Message message) {
        Object death = firstDeath(message);
        if (death instanceof Map<?, ?> map) {
            Object reason = map.get("reason");
            Object time = map.get("time");
            return "x-death reason=" + reason + " at=" + time;
        }
        return "no x-death header";
    }

    private static Object firstDeath(Message message) {
        List<Map<String, ?>> deaths = message.getMessageProperties().getXDeathHeader();
        return (deaths == null || deaths.isEmpty()) ? null : deaths.get(0);
    }

    private static String header(Message message, String name) {
        Object value = message.getMessageProperties().getHeader(name);
        return value == null ? null : String.valueOf(value);
    }

    private static DeadLetterAudit mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        DeadLetterAudit audit = new DeadLetterAudit();
        audit.setId(rs.getLong("id"));
        audit.setConsumerGroup(rs.getString("consumer_group"));
        audit.setSourceQueue(rs.getString("source_queue"));
        audit.setEventId(rs.getString("event_id"));
        audit.setEventType(rs.getString("event_type"));
        audit.setAggregateId(rs.getString("aggregate_id"));
        audit.setCorrelationId(rs.getString("correlation_id"));
        audit.setPayload(rs.getString("payload"));
        audit.setFailureReason(rs.getString("failure_reason"));
        audit.setReplayed(rs.getInt("replayed") == 1);
        audit.setReplayEventId(rs.getString("replay_event_id"));
        java.sql.Timestamp created = rs.getTimestamp("created_at");
        audit.setCreatedAt(created == null ? null : created.toLocalDateTime());
        java.sql.Timestamp replayedAt = rs.getTimestamp("replayed_at");
        audit.setReplayedAt(replayedAt == null ? null : replayedAt.toLocalDateTime());
        return audit;
    }
}
