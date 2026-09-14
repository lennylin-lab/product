package com.product.cloud.messaging;

import com.product.cloud.messaging.outbox.OutboxDao;
import com.product.cloud.messaging.outbox.OutboxMessage;
import com.product.cloud.messaging.outbox.OutboxRelay;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * outbox 退避与重放信封还原测试（ADR-0004 §3/§6）。
 */
class OutboxSemanticsTest {

    @Test
    void backoffShouldGrowExponentiallyAndCap() {
        assertEquals(1000L, OutboxDao.backoff(1, 1000, 2.0, 300000));
        assertEquals(2000L, OutboxDao.backoff(2, 1000, 2.0, 300000));
        assertEquals(4000L, OutboxDao.backoff(3, 1000, 2.0, 300000));
        assertEquals(300000L, OutboxDao.backoff(20, 1000, 2.0, 300000));
    }

    @Test
    void toEnvelopeShouldRestoreOccurredAtCorrelationAndPayload() {
        OutboxMessage message = new OutboxMessage();
        message.setEventId("aaaa-bbbb");
        message.setEventType("task.status.changed");
        message.setAggregateId("123");
        message.setCorrelationId("trace-1");
        message.setProducer("product-execution");
        message.setPayload("{\"taskId\":123,\"targetStatus\":\"RUNNING\"}");
        message.setOccurredAt(LocalDateTime.of(2026, 9, 15, 10, 0, 0, 123_000_000));

        var envelope = OutboxRelay.toEnvelope(message);

        assertEquals("aaaa-bbbb", envelope.getEventId());
        assertEquals("task.status.changed", envelope.getEventType());
        assertEquals("123", envelope.getAggregateId());
        assertEquals("trace-1", envelope.getCorrelationId());
        assertEquals("product-execution", envelope.getProducer());
        assertEquals(1, envelope.getVersion());
        assertTrue(envelope.getOccurredAt().startsWith("2026-09-15T10:00:00.123"));
        assertEquals(123, ((Number) envelope.getPayload().get("taskId")).intValue());
        assertEquals("RUNNING", envelope.getPayload().get("targetStatus"));
    }
}
