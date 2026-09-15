package com.product.planning.event;

import com.product.cloud.messaging.api.EventEnvelope;
import com.product.cloud.messaging.api.EventTypes;
import com.product.cloud.messaging.consume.ConsumedEvent;
import com.product.cloud.messaging.consume.ConsumedEventRecorder;
import com.product.cloud.messaging.outbox.OutboxPublisher;
import com.product.masterdata.api.ResourceStatusUpdateApi;
import com.product.masterdata.api.dto.ResourceStatusUpdateResponse;
import com.product.planning.service.impl.RescheduleTriggerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 消费侧重排触发裁决测试（KD2/R4，09-16-reschedule-trigger；与既有
 * PlanningEventConsumerServiceTest 互不影响——本文件为纯增量）：
 * 触发集合裁决（DOWN/AVAILABLE 触发，MAINTENANCE/OFFSHIFT/BUSY 只回写不触发）、
 * 顺序契约（回写 → 触发 → record(APPLIED)）、触发失败不使消费失败、
 * EXCEPTION/task.status.changed 路径零改动（绝不触碰触发链）。
 */
class PlanningEventConsumerRescheduleTriggerTest {

    private ConsumedEventRecorder recorder;
    private ResourceStatusUpdateApi resourceStatusUpdateApi;
    private RescheduleTriggerService rescheduleTriggerService;
    private PlanningEventConsumerService service;

    /** DB 应用桩：task.status.changed 的落库路径跳过 Db 工具链（离线单测无数据源）。 */
    private static final class DbStubbedConsumerService extends PlanningEventConsumerService {
        private DbStubbedConsumerService(ConsumedEventRecorder recorder,
                                         ResourceStatusUpdateApi api,
                                         RescheduleTriggerService trigger) {
            super(recorder, mock(OutboxPublisher.class), api, trigger);
        }

        @Override
        public String applyTaskStatusChanged(EventEnvelope envelope) {
            return ConsumedEvent.OUTCOME_APPLIED;
        }
    }

    @BeforeEach
    void setUp() {
        recorder = mock(ConsumedEventRecorder.class);
        resourceStatusUpdateApi = mock(ResourceStatusUpdateApi.class);
        rescheduleTriggerService = mock(RescheduleTriggerService.class);
        service = new DbStubbedConsumerService(recorder, resourceStatusUpdateApi, rescheduleTriggerService);
    }

    private EventEnvelope resourceEvent(String eventId, String toStatus) {
        EventEnvelope envelope = new EventEnvelope();
        envelope.setEventId(eventId);
        envelope.setEventType(EventTypes.RESOURCE_STATUS_CHANGED);
        envelope.setOccurredAt("2026-09-16T10:00:00+08:00");
        envelope.setAggregateId("301");
        envelope.setCorrelationId("trace-reschedule");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("resourceId", 301L);
        payload.put("fromStatus", "AVAILABLE");
        payload.put("toStatus", toStatus);
        payload.put("reasonCode", "EQUIP_FAULT");
        payload.put("occurredEventId", 9001L);
        envelope.setPayload(payload);
        return envelope;
    }

    private void stubWriteBack(long resourceId, String status) {
        when(recorder.alreadyConsumed(anyString(), anyString())).thenReturn(false);
        ResourceStatusUpdateResponse response = new ResourceStatusUpdateResponse();
        response.setResourceId(resourceId);
        response.setStatus(status);
        response.setSnapshotVersion(6L);
        doReturn(response).when(resourceStatusUpdateApi).updateResourceStatus(any());
    }

    @Test
    void downEventShouldTriggerRescheduleBetweenWriteBackAndRecord() {
        stubWriteBack(301L, "DOWN");

        String outcome = service.consumeResourceStatusChanged(resourceEvent("evt-down", "DOWN"));

        assertEquals(ConsumedEvent.OUTCOME_APPLIED, outcome);
        // 顺序契约：回写成功（前置）→ 触发重排 → record(APPLIED)
        InOrder inOrder = inOrder(resourceStatusUpdateApi, rescheduleTriggerService, recorder);
        inOrder.verify(resourceStatusUpdateApi).updateResourceStatus(any());
        inOrder.verify(rescheduleTriggerService).requestReschedule();
        inOrder.verify(recorder).record(anyString(), any(EventEnvelope.class), eq(ConsumedEvent.OUTCOME_APPLIED));
    }

    @Test
    void availableEventShouldTriggerReschedule() {
        // 恢复事件（toStatus=AVAILABLE）同属触发集合（AC4）
        stubWriteBack(301L, "AVAILABLE");

        String outcome = service.consumeResourceStatusChanged(resourceEvent("evt-avail", "AVAILABLE"));

        assertEquals(ConsumedEvent.OUTCOME_APPLIED, outcome);
        verify(rescheduleTriggerService).requestReschedule();
        verify(recorder).record(anyString(), any(EventEnvelope.class), eq(ConsumedEvent.OUTCOME_APPLIED));
    }

    @Test
    void nonTriggerStatusesShouldOnlyWriteBackWithoutTrigger() {
        // AC4：MAINTENANCE/OFFSHIFT/BUSY 只回写不触发（BUSY 亦然）
        int expectedWriteBacks = 0;
        for (String status : new String[]{"MAINTENANCE", "OFFSHIFT", "BUSY"}) {
            expectedWriteBacks++;
            stubWriteBack(301L, status);

            String outcome = service.consumeResourceStatusChanged(resourceEvent("evt-" + status, status));

            assertEquals(ConsumedEvent.OUTCOME_APPLIED, outcome);
            verify(resourceStatusUpdateApi, org.mockito.Mockito.times(expectedWriteBacks))
                    .updateResourceStatus(any());
            verify(recorder, org.mockito.Mockito.times(expectedWriteBacks))
                    .record(anyString(), any(EventEnvelope.class), eq(ConsumedEvent.OUTCOME_APPLIED));
        }
        verify(rescheduleTriggerService, never()).requestReschedule();
    }

    @Test
    void triggerFailureShouldNotFailConsumption() {
        // 触发动作绝不让消费失败进入重试/DLX：触发层抛错 → 照常 record(APPLIED)
        stubWriteBack(301L, "DOWN");
        doThrow(new IllegalStateException("trigger blew up"))
                .when(rescheduleTriggerService).requestReschedule();

        String outcome = service.consumeResourceStatusChanged(resourceEvent("evt-trigfail", "DOWN"));

        assertEquals(ConsumedEvent.OUTCOME_APPLIED, outcome);
        verify(recorder).record(anyString(), any(EventEnvelope.class), eq(ConsumedEvent.OUTCOME_APPLIED));
    }

    @Test
    void writeBackFailureShouldNeverReachTrigger() {
        // 顺序契约：回写失败（fail-closed 抛错）→ 绝不触发重排、不记流水
        when(recorder.alreadyConsumed(anyString(), anyString())).thenReturn(false);
        doThrow(new RuntimeException("connect refused"))
                .when(resourceStatusUpdateApi).updateResourceStatus(any());

        try {
            service.consumeResourceStatusChanged(resourceEvent("evt-wbfail", "DOWN"));
        } catch (RuntimeException expected) {
            // fail-closed 不 ack
        }
        verify(rescheduleTriggerService, never()).requestReschedule();
        verify(recorder, never()).record(anyString(), any(EventEnvelope.class), anyString());
    }

    @Test
    void duplicateResourceEventShouldSkipTrigger() {
        // 幂等检查在前：重复事件不回写也不触发
        when(recorder.alreadyConsumed(anyString(), anyString())).thenReturn(true);

        String outcome = service.consumeResourceStatusChanged(resourceEvent("evt-dup", "DOWN"));

        assertEquals(ConsumedEvent.OUTCOME_APPLIED, outcome);
        verify(rescheduleTriggerService, never()).requestReschedule();
    }

    @Test
    void exceptionTaskEventShouldNeverTouchRescheduleTrigger() {
        // EXCEPTION/task.status.changed 消费路径零改动：不触碰重排触发链（无容量变化）
        when(recorder.alreadyConsumed(anyString(), anyString())).thenReturn(false);

        EventEnvelope envelope = new EventEnvelope();
        envelope.setEventId("evt-exc");
        envelope.setEventType(EventTypes.TASK_STATUS_CHANGED);
        envelope.setOccurredAt("2026-09-16T10:00:00+08:00");
        envelope.setAggregateId("7900000003");
        envelope.setCorrelationId("trace-exc");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", 7900000003L);
        payload.put("eventType", "EXCEPTION");
        payload.put("targetStatus", "PAUSED");
        payload.put("reasonCode", "MATERIAL_SHORTAGE");
        envelope.setPayload(payload);

        String outcome = service.consumeTaskStatusChanged(envelope);

        assertEquals(ConsumedEvent.OUTCOME_APPLIED, outcome);
        verify(recorder).record(anyString(), any(EventEnvelope.class), eq(ConsumedEvent.OUTCOME_APPLIED));
        verify(rescheduleTriggerService, never()).requestReschedule();
    }
}
