package com.product.cloud.messaging.consume;

import com.product.cloud.messaging.api.EventEnvelope;
import com.product.cloud.messaging.codec.EnvelopeCodec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.OffsetDateTime;

/**
 * 消费幂等记录器（ADR-0004 §4）。
 *
 * <p>流程约定（消费方在同一个业务事务内完成，任一步失败整体回滚重投）：</p>
 * <ol>
 *   <li>{@link #alreadyConsumed}：快速跳过重复投递（同 eventId 直接 ack）；</li>
 *   <li>{@link #lastAppliedAt}：同聚合单调性守卫——早于该聚合最近已应用事件时间的
 *       过期事件记录后丢弃（不回滚已提交状态）；</li>
 *   <li>业务应用成功后 {@link #record}：写消费流水（UNIQUE(consumer_group,event_id)
 *       兜底并发重复；重复时按已消费处理返回 false）。</li>
 * </ol>
 */
@Slf4j
public class ConsumedEventRecorder {

    private final JdbcTemplate jdbc;

    public ConsumedEventRecorder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 是否已消费过该事件（同 consumerGroup + eventId）。 */
    public boolean alreadyConsumed(String consumerGroup, String eventId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM consumed_event WHERE consumer_group = ? AND event_id = ?",
                Integer.class, consumerGroup, eventId);
        return count != null && count > 0;
    }

    /** 该消费组对某聚合最近一次已应用（APPLIED）的事件时间；无记录返回 null。 */
    public OffsetDateTime lastAppliedAt(String consumerGroup, String aggregateId) {
        if (aggregateId == null) {
            return null;
        }
        Timestamp latest = jdbc.queryForObject("""
                        SELECT MAX(occurred_at) FROM consumed_event
                        WHERE consumer_group = ? AND aggregate_id = ? AND outcome = ?
                        """, Timestamp.class,
                consumerGroup, aggregateId, ConsumedEvent.OUTCOME_APPLIED);
        return latest == null ? null : latest.toLocalDateTime().atOffset(OffsetDateTime.now().getOffset());
    }

    /**
     * 记录消费流水（须与业务写同事务）。eventId 重复（并发兜底）返回 false。
     */
    public boolean record(String consumerGroup, EventEnvelope envelope, String outcome) {
        try {
            jdbc.update("""
                            INSERT INTO consumed_event
                                (consumer_group, event_id, event_type, aggregate_id, occurred_at,
                                 outcome, payload, consumed_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, NOW(3))
                            """,
                    consumerGroup, envelope.getEventId(), envelope.getEventType(),
                    envelope.getAggregateId(), occurredAt(envelope), outcome,
                    EnvelopeCodec.mapper().valueToTree(envelope.getPayload()).toString());
            return true;
        } catch (DuplicateKeyException e) {
            log.info("消费流水重复（并发兜底跳过）: group={} eventId={}", consumerGroup, envelope.getEventId());
            return false;
        }
    }

    private static Timestamp occurredAt(EventEnvelope envelope) {
        try {
            return Timestamp.valueOf(EnvelopeCodec.parseOccurredAt(envelope.getOccurredAt()).toLocalDateTime());
        } catch (Exception e) {
            return null;
        }
    }
}
