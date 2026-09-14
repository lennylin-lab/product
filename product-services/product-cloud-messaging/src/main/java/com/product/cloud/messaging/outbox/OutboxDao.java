package com.product.cloud.messaging.outbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;

/**
 * 发件箱 DAO（JdbcTemplate；与使用服务的 MyBatis 共享同一 DataSourceTransactionManager，
 * 保证"业务写 + outbox 写"原子性，ADR-0004 §3）。
 */
public class OutboxDao {

    private static final RowMapper<OutboxMessage> ROW_MAPPER = new OutboxRowMapper();

    private final JdbcTemplate jdbc;

    public OutboxDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 追加一条待发布事件（须在业务写事务内调用）。返回分配的 outbox 行 ID。 */
    public long insert(OutboxMessage message) {
        jdbc.update("""
                        INSERT INTO event_outbox
                            (event_id, event_type, aggregate_id, correlation_id, producer, payload,
                             status, retry_count, replayed_from, occurred_at, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(3))
                        """,
                message.getEventId(), message.getEventType(), message.getAggregateId(),
                message.getCorrelationId(), message.getProducer(), message.getPayload(),
                OutboxMessage.STATUS_PENDING, 0, message.getReplayedFrom(), message.getOccurredAt());
        Long id = jdbc.queryForObject("SELECT id FROM event_outbox WHERE event_id = ?", Long.class,
                message.getEventId());
        return id == null ? 0L : id;
    }

    /** 取待投递行（按 id 严格升序 = 事件追加顺序；PENDING 且到期）。 */
    public List<OutboxMessage> selectPending(int limit) {
        return jdbc.query("""
                        SELECT * FROM event_outbox
                        WHERE status = 'PENDING' AND (next_retry_at IS NULL OR next_retry_at <= NOW(3))
                        ORDER BY id ASC LIMIT ?
                        """, ROW_MAPPER, limit);
    }

    /** 按主键查询。 */
    public OutboxMessage findById(long id) {
        List<OutboxMessage> rows = jdbc.query("SELECT * FROM event_outbox WHERE id = ?", ROW_MAPPER, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 通用查询（人工核对/筛选；ORDER BY/LIMIT 由调用方 SQL 拼装，服务内部使用）。 */
    public List<OutboxMessage> query(String sql, Object... args) {
        return jdbc.query(sql, ROW_MAPPER, args);
    }

    /** 各状态行计数（对账/巡检用）。 */
    public long countByStatus(String status) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM event_outbox WHERE status = ?", Long.class, status);
        return count == null ? 0L : count;
    }

    /** 投递成功（confirm 收到后调用）。 */
    public void markPublished(long id) {
        jdbc.update("UPDATE event_outbox SET status = ?, published_at = NOW(3) WHERE id = ?",
                OutboxMessage.STATUS_PUBLISHED, id);
    }

    /** 投递失败：递增计数并安排退避；超限转 HALTED（人工处理）。 */
    public void markFailed(OutboxMessage message, int maxAttempts, long backoffInitialMs,
                           double backoffMultiplier, long backoffMaxMs) {
        int nextCount = (message.getRetryCount() == null ? 0 : message.getRetryCount()) + 1;
        if (nextCount >= maxAttempts) {
            jdbc.update("UPDATE event_outbox SET status = ?, retry_count = ?, next_retry_at = NULL WHERE id = ?",
                    OutboxMessage.STATUS_HALTED, nextCount, message.getId());
            return;
        }
        long delayMs = backoff(nextCount, backoffInitialMs, backoffMultiplier, backoffMaxMs);
        jdbc.update("""
                        UPDATE event_outbox SET retry_count = ?, next_retry_at = DATE_ADD(NOW(3), INTERVAL ? MICROSECOND)
                        WHERE id = ?
                        """, nextCount, delayMs * 1000, message.getId());
    }

    /** 退避序列：initial * multiplier^(n-1)，封顶 max。 */
    public static long backoff(int attempt, long initialMs, double multiplier, long maxMs) {
        double delay = initialMs * Math.pow(multiplier, Math.max(0, attempt - 1));
        return (long) Math.min(maxMs, Math.max(initialMs, delay));
    }

    private static final class OutboxRowMapper implements RowMapper<OutboxMessage> {
        @Override
        public OutboxMessage mapRow(ResultSet rs, int rowNum) throws SQLException {
            OutboxMessage m = new OutboxMessage();
            m.setId(rs.getLong("id"));
            m.setEventId(rs.getString("event_id"));
            m.setEventType(rs.getString("event_type"));
            m.setAggregateId(rs.getString("aggregate_id"));
            m.setCorrelationId(rs.getString("correlation_id"));
            m.setProducer(rs.getString("producer"));
            m.setPayload(rs.getString("payload"));
            m.setStatus(rs.getString("status"));
            m.setRetryCount(rs.getInt("retry_count"));
            Timestamp nextRetry = rs.getTimestamp("next_retry_at");
            m.setNextRetryAt(nextRetry == null ? null : nextRetry.toLocalDateTime());
            m.setReplayedFrom(rs.getString("replayed_from"));
            Timestamp occurred = rs.getTimestamp("occurred_at");
            m.setOccurredAt(occurred == null ? null : occurred.toLocalDateTime());
            Timestamp created = rs.getTimestamp("created_at");
            m.setCreatedAt(created == null ? null : created.toLocalDateTime());
            Timestamp published = rs.getTimestamp("published_at");
            m.setPublishedAt(published == null ? null : published.toLocalDateTime());
            return m;
        }
    }
}
