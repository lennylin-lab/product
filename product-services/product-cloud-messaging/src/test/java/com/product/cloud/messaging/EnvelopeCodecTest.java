package com.product.cloud.messaging;

import com.product.cloud.messaging.api.EventEnvelope;
import com.product.cloud.messaging.api.EventTypes;
import com.product.cloud.messaging.codec.EnvelopeCodec;
import com.product.cloud.messaging.config.MessagingCodecException;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * envelope v1 契约编解码测试（ADR-0004 §2）。
 */
class EnvelopeCodecTest {

    private EventEnvelope sample() {
        EventEnvelope envelope = new EventEnvelope();
        envelope.setEventId("11111111-2222-3333-4444-555555555555");
        envelope.setEventType(EventTypes.TASK_STATUS_CHANGED);
        envelope.setVersion(1);
        envelope.setOccurredAt("2026-09-15T10:00:00.123+08:00");
        envelope.setProducer("product-execution");
        envelope.setAggregateId("7900000001");
        envelope.setCorrelationId("trace-abc");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", 7900000001L);
        payload.put("eventType", "START");
        payload.put("targetStatus", "RUNNING");
        payload.put("resourceId", null);
        payload.put("occurredEventId", 7900000002L);
        envelope.setPayload(payload);
        return envelope;
    }

    @Test
    void roundTripShouldPreserveAllEnvelopeFields() {
        EventEnvelope parsed = EnvelopeCodec.parse(EnvelopeCodec.encode(sample()));
        assertEquals("11111111-2222-3333-4444-555555555555", parsed.getEventId());
        assertEquals(EventTypes.TASK_STATUS_CHANGED, parsed.getEventType());
        assertEquals(1, parsed.getVersion());
        assertEquals("2026-09-15T10:00:00.123+08:00", parsed.getOccurredAt());
        assertEquals("product-execution", parsed.getProducer());
        assertEquals("7900000001", parsed.getAggregateId());
        assertEquals("trace-abc", parsed.getCorrelationId());
        assertEquals(7900000001L, parsed.getPayload().get("taskId"));
        assertEquals("START", parsed.getPayload().get("eventType"));
        assertEquals("RUNNING", parsed.getPayload().get("targetStatus"));
        assertTrue(parsed.getPayload().containsKey("resourceId"));
        assertEquals(7900000002L, parsed.getPayload().get("occurredEventId"));
    }

    @Test
    void unknownEnvelopeFieldsShouldBeIgnoredOnParse() {
        byte[] encoded = EnvelopeCodec.encode(sample());
        // envelope 层未知字段（向前兼容：v2 新增字段被 v1 消费方忽略，ADR-0004 演进策略）
        String json = new String(encoded).replaceFirst(
                "\"version\":1,", "\"version\":1,\"futureEnvelopeField\":{\"a\":1},");
        EventEnvelope parsed = EnvelopeCodec.parse(json.getBytes());
        assertEquals("7900000001", parsed.getAggregateId());
        assertFalse(parsed.getPayload().containsKey("futureEnvelopeField"));
        // payload 内未知键是 Map 数据（只加不改策略下原样保留）
        assertTrue(parsed.getPayload().containsKey("taskId"));
    }

    @Test
    void occurredAtParsingShouldRoundTripAndRejectGarbage() {
        String now = EnvelopeCodec.now();
        OffsetDateTime parsed = EnvelopeCodec.parseOccurredAt(now);
        assertNotNull(parsed);
        assertThrows(MessagingCodecException.class, () -> EnvelopeCodec.parseOccurredAt("not-a-time"));
        assertThrows(MessagingCodecException.class, () -> EnvelopeCodec.parseOccurredAt(null));
    }

    @Test
    void structurallyInvalidBodyShouldFailWithCodecException() {
        assertThrows(MessagingCodecException.class, () -> EnvelopeCodec.parse("not json".getBytes()));
    }
}
