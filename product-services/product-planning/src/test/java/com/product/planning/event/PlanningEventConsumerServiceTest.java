package com.product.planning.event;

import com.product.cloud.messaging.api.EventEnvelope;
import com.product.cloud.messaging.api.EventTypes;
import com.product.cloud.messaging.codec.EnvelopeCodec;
import com.product.cloud.messaging.consume.ConsumedEvent;
import com.product.cloud.messaging.consume.ConsumedEventRecorder;
import com.product.planning.common.exception.ServiceException;
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
 * 计划域消费编排测试（eventId 幂等 + occurredAt 单调性守卫；DB 应用逻辑经子类桩替换，
 * 实库行为由 live 对拍与异常语义实测覆盖，scratch/phase5/）。
 */
class PlanningEventConsumerServiceTest {

    private ConsumedEventRecorder recorder;
    private TestableConsumerService service;

    /** DB 应用桩：跳过 Db 工具链（离线单测无数据源），只回传编排需要的结果。 */
    private static final class TestableConsumerService extends PlanningEventConsumerService {
        private String applyOutcome = ConsumedEvent.OUTCOME_APPLIED;

        private TestableConsumerService(ConsumedEventRecorder recorder) {
            super(recorder, Mockito.mock(com.product.cloud.messaging.outbox.OutboxPublisher.class));
        }

        @Override
        public String applyTaskStatusChanged(EventEnvelope envelope) {
            return applyOutcome;
        }
    }

    @BeforeEach
    void setUp() {
        recorder = mock(ConsumedEventRecorder.class);
        service = new TestableConsumerService(recorder);
    }

    private EventEnvelope taskEvent(String eventId, String occurredAt, String aggregateId) {
        EventEnvelope envelope = new EventEnvelope();
        envelope.setEventId(eventId);
        envelope.setEventType(EventTypes.TASK_STATUS_CHANGED);
        envelope.setOccurredAt(occurredAt);
        envelope.setAggregateId(aggregateId);
        envelope.setCorrelationId("trace-1");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", 7900000001L);
        payload.put("eventType", "START");
        payload.put("targetStatus", "RUNNING");
        envelope.setPayload(payload);
        return envelope;
    }

    @Test
    void duplicateEventShouldSkipWithoutApplying() {
        when(recorder.alreadyConsumed(anyString(), eq("evt-1"))).thenReturn(true);

        String outcome = service.consumeTaskStatusChanged(taskEvent("evt-1",
                "2026-09-15T10:00:00+08:00", "7900000001"));

        assertEquals(ConsumedEvent.OUTCOME_APPLIED, outcome);
        // 重复投递：不重复记流水、不重复应用
        verify(recorder, never()).record(anyString(), any(EventEnvelope.class), anyString());
    }

    @Test
    void staleEventShouldBeRecordedAndDiscarded() {
        when(recorder.alreadyConsumed(anyString(), eq("evt-2"))).thenReturn(false);
        when(recorder.lastAppliedAt(anyString(), eq("7900000001"))).thenReturn(
                OffsetDateTime.parse("2026-09-15T10:05:00+08:00"));

        String outcome = service.consumeTaskStatusChanged(taskEvent("evt-2",
                "2026-09-15T10:00:00+08:00", "7900000001"));

        // ADR-0004 §4：过期事件记录后丢弃
        assertEquals(ConsumedEvent.OUTCOME_STALE, outcome);
        verify(recorder).record(anyString(), any(EventEnvelope.class), eq(ConsumedEvent.OUTCOME_STALE));
    }

    @Test
    void freshEventShouldApplyThenRecord() {
        when(recorder.alreadyConsumed(anyString(), eq("evt-3"))).thenReturn(false);
        when(recorder.lastAppliedAt(anyString(), eq("7900000001"))).thenReturn(
                OffsetDateTime.parse("2026-09-15T09:55:00+08:00"));

        String outcome = service.consumeTaskStatusChanged(taskEvent("evt-3",
                "2026-09-15T10:00:00+08:00", "7900000001"));

        assertEquals(ConsumedEvent.OUTCOME_APPLIED, outcome);
        verify(recorder).record(anyString(), any(EventEnvelope.class), eq(ConsumedEvent.OUTCOME_APPLIED));
    }

    @Test
    void malformedPayloadShouldThrowForRetryAndDeadLetter() {
        EventEnvelope bad = taskEvent("evt-4", "2026-09-15T10:00:00+08:00", "7900000001");
        bad.getPayload().remove("targetStatus");

        assertThrows(ServiceException.class, () -> service.consumeTaskStatusChanged(bad));
        verify(recorder, never()).record(anyString(), any(EventEnvelope.class), anyString());
    }

    @Test
    void unknownEventTypeShouldThrow() {
        EventEnvelope wrongType = taskEvent("evt-5", "2026-09-15T10:00:00+08:00", "7900000001");
        wrongType.setEventType("batch.progress.changed");

        assertThrows(ServiceException.class, () -> service.consumeTaskStatusChanged(wrongType));
    }

    @Test
    void occurredAtParsingIsDelegatedToCodec() {
        // 合法 occurredAt 的解析走 codec（格式契约在 EnvelopeCodecTest 覆盖）
        assertEquals(ConsumedEvent.OUTCOME_APPLIED, service.consumeTaskStatusChanged(
                taskEvent("evt-6", EnvelopeCodec.now(), "7900000001")));
    }
}
