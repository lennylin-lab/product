package com.product.demand.event;

import com.product.cloud.messaging.api.EventEnvelope;
import com.product.cloud.messaging.api.EventTypes;
import com.product.cloud.messaging.codec.EnvelopeCodec;
import com.product.cloud.messaging.consume.ConsumedEvent;
import com.product.cloud.messaging.consume.ConsumedEventRecorder;
import com.product.demand.common.exception.ServiceException;
import com.product.demand.service.DemandDataVersionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 需求域消费编排测试（eventId 幂等 + occurredAt 单调性守卫；DB 应用逻辑经子类桩替换，
 * 实库行为由 live 对拍与异常语义实测覆盖，scratch/phase5/）。
 */
class DemandEventConsumerServiceTest {

    private ConsumedEventRecorder recorder;
    private TestableConsumerService service;

    /** DB 应用桩：跳过 Db 工具链（离线单测无数据源）。 */
    private static final class TestableConsumerService extends DemandEventConsumerService {
        private String applyOutcome = ConsumedEvent.OUTCOME_APPLIED;

        private TestableConsumerService(ConsumedEventRecorder recorder) {
            super(recorder,
                    Mockito.mock(com.product.cloud.messaging.outbox.OutboxPublisher.class),
                    mock(DemandDataVersionService.class));
        }

        @Override
        public String applyBatchProgress(EventEnvelope envelope, Map<String, Object> payload) {
            return applyOutcome;
        }
    }

    @BeforeEach
    void setUp() {
        recorder = mock(ConsumedEventRecorder.class);
        service = new TestableConsumerService(recorder);
    }

    private EventEnvelope batchEvent(String eventId, String occurredAt, String batchId) {
        EventEnvelope envelope = new EventEnvelope();
        envelope.setEventId(eventId);
        envelope.setEventType(EventTypes.BATCH_PROGRESS_CHANGED);
        envelope.setOccurredAt(occurredAt);
        envelope.setAggregateId(batchId);
        envelope.setCorrelationId("trace-2");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("batchId", 7900000100L);
        payload.put("orderLineId", 1001L);
        payload.put("batchStatus", "IN_PROCESS");
        envelope.setPayload(payload);
        return envelope;
    }

    @Test
    void duplicateEventShouldSkipWithoutApplying() {
        when(recorder.alreadyConsumed(anyString(), eq("evt-1"))).thenReturn(true);

        String outcome = service.consumeBatchProgressChanged(batchEvent("evt-1",
                "2026-09-15T10:00:00+08:00", "7900000100"));

        assertEquals(ConsumedEvent.OUTCOME_APPLIED, outcome);
        verify(recorder, never()).record(anyString(), any(EventEnvelope.class), anyString());
    }

    @Test
    void staleBatchEventShouldBeRecordedAndDiscarded() {
        when(recorder.alreadyConsumed(anyString(), eq("evt-2"))).thenReturn(false);
        when(recorder.lastAppliedAt(anyString(), eq("7900000100"))).thenReturn(
                OffsetDateTime.parse("2026-09-15T10:05:00+08:00"));

        String outcome = service.consumeBatchProgressChanged(batchEvent("evt-2",
                "2026-09-15T10:00:00+08:00", "7900000100"));

        assertEquals(ConsumedEvent.OUTCOME_STALE, outcome);
        verify(recorder).record(anyString(), any(EventEnvelope.class), eq(ConsumedEvent.OUTCOME_STALE));
    }

    @Test
    void freshBatchEventShouldApplyThenRecord() {
        when(recorder.alreadyConsumed(anyString(), eq("evt-3"))).thenReturn(false);
        when(recorder.lastAppliedAt(anyString(), eq("7900000100"))).thenReturn(null);

        String outcome = service.consumeBatchProgressChanged(batchEvent("evt-3",
                EnvelopeCodec.now(), "7900000100"));

        assertEquals(ConsumedEvent.OUTCOME_APPLIED, outcome);
        verify(recorder).record(anyString(), any(EventEnvelope.class), eq(ConsumedEvent.OUTCOME_APPLIED));
    }

    @Test
    void malformedPayloadShouldThrowForRetryAndDeadLetter() {
        EventEnvelope bad = batchEvent("evt-4", EnvelopeCodec.now(), "7900000100");
        bad.getPayload().remove("batchStatus");

        assertThrows(ServiceException.class, () -> service.consumeBatchProgressChanged(bad));
        verify(recorder, never()).record(anyString(), any(EventEnvelope.class), anyString());
    }

    @Test
    void unknownEventTypeShouldThrow() {
        EventEnvelope wrongType = batchEvent("evt-5", EnvelopeCodec.now(), "7900000100");
        wrongType.setEventType("task.status.changed");

        assertThrows(ServiceException.class, () -> service.consumeBatchProgressChanged(wrongType));
    }
}
