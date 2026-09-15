package com.product.planning.event;

import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper;
import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.product.cloud.messaging.api.EventEnvelope;
import com.product.cloud.messaging.api.EventTypes;
import com.product.cloud.messaging.codec.EnvelopeCodec;
import com.product.cloud.messaging.consume.ConsumedEvent;
import com.product.cloud.messaging.consume.ConsumedEventRecorder;
import com.product.masterdata.api.ResourceStatusUpdateApi;
import com.product.masterdata.api.dto.ResourceStatusUpdateRequest;
import com.product.masterdata.api.dto.ResourceStatusUpdateResponse;
import com.product.planning.common.exception.ServiceException;
import com.product.planning.domain.entity.OperationTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 计划域消费编排测试（eventId 幂等 + occurredAt 单调性守卫；DB 应用逻辑经子类桩替换，
 * 实库行为由 live 对拍与异常语义实测覆盖，scratch/phase5/）。
 *
 * <p>2026-09-16 增量（KD3/异常事件建模）：resource.status.changed 消费回写 master-data
 * 权威状态（fail-closed 不 ack）；EXCEPTION 任务事件复用既有 PAUSED 落库路径（mockStatic Db
 * 验证无条件映射落库，零新逻辑）。</p>
 */
class PlanningEventConsumerServiceTest {

    private ConsumedEventRecorder recorder;
    private ResourceStatusUpdateApi resourceStatusUpdateApi;
    private TestableConsumerService service;

    /** DB 应用桩：跳过 Db 工具链（离线单测无数据源），只回传编排需要的结果。 */
    private static final class TestableConsumerService extends PlanningEventConsumerService {
        private String applyOutcome = ConsumedEvent.OUTCOME_APPLIED;
        private boolean stubApply = true;

        private TestableConsumerService(ConsumedEventRecorder recorder,
                                        ResourceStatusUpdateApi resourceStatusUpdateApi) {
            super(recorder, Mockito.mock(com.product.cloud.messaging.outbox.OutboxPublisher.class),
                    resourceStatusUpdateApi);
        }

        @Override
        public String applyTaskStatusChanged(EventEnvelope envelope) {
            return stubApply ? applyOutcome : super.applyTaskStatusChanged(envelope);
        }
    }

    @BeforeEach
    void setUp() {
        recorder = mock(ConsumedEventRecorder.class);
        resourceStatusUpdateApi = mock(ResourceStatusUpdateApi.class);
        service = new TestableConsumerService(recorder, resourceStatusUpdateApi);
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

    private EventEnvelope resourceEvent(String eventId) {
        EventEnvelope envelope = new EventEnvelope();
        envelope.setEventId(eventId);
        envelope.setEventType(EventTypes.RESOURCE_STATUS_CHANGED);
        envelope.setOccurredAt("2026-09-16T10:00:00+08:00");
        envelope.setAggregateId("301");
        envelope.setCorrelationId("trace-2");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("resourceId", 301L);
        payload.put("fromStatus", "AVAILABLE");
        payload.put("toStatus", "DOWN");
        payload.put("reasonCode", "EQUIP_FAULT");
        payload.put("occurredEventId", 9001L);
        envelope.setPayload(payload);
        return envelope;
    }

    private ResourceStatusUpdateResponse writeBackResponse(long resourceId, String status) {
        ResourceStatusUpdateResponse response = new ResourceStatusUpdateResponse();
        response.setResourceId(resourceId);
        response.setStatus(status);
        response.setSnapshotVersion(6L);
        return response;
    }

    // ---- 既有消费语义回归（task.status.changed）----

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

    // ---- KD3 资源状态回写（resource.status.changed 消费升级）----

    @Test
    void resourceEventShouldWriteBackThenRecordApplied() {
        when(recorder.alreadyConsumed(anyString(), eq("res-1"))).thenReturn(false);
        when(resourceStatusUpdateApi.updateResourceStatus(any()))
                .thenReturn(writeBackResponse(301L, "DOWN"));

        String outcome = service.consumeResourceStatusChanged(resourceEvent("res-1"));

        assertEquals(ConsumedEvent.OUTCOME_APPLIED, outcome);
        ArgumentCaptor<ResourceStatusUpdateRequest> captor =
                ArgumentCaptor.forClass(ResourceStatusUpdateRequest.class);
        verify(resourceStatusUpdateApi).updateResourceStatus(captor.capture());
        assertEquals(301L, captor.getValue().getResourceId());
        assertEquals("DOWN", captor.getValue().getToStatus());
        assertEquals("EQUIP_FAULT", captor.getValue().getReasonCode());
        // 回写成功才记 APPLIED 流水（顺序契约：回写成功是后续任何触发的前置）
        verify(recorder).record(anyString(), any(EventEnvelope.class), eq(ConsumedEvent.OUTCOME_APPLIED));
    }

    @Test
    void resourceEventWriteBackFailureShouldThrowWithoutAck() {
        // fail-closed：Feign 失败/超时 → 抛错不 ack（无 consumed_event 行 → 重试/DLX 兜底）
        when(recorder.alreadyConsumed(anyString(), eq("res-2"))).thenReturn(false);
        when(resourceStatusUpdateApi.updateResourceStatus(any()))
                .thenThrow(new RuntimeException("connect refused"));

        assertThrows(RuntimeException.class, () -> service.consumeResourceStatusChanged(resourceEvent("res-2")));
        verify(recorder, never()).record(anyString(), any(EventEnvelope.class), anyString());
    }

    @Test
    void resourceEventProviderRejectionShouldThrowWithoutAck() {
        // 提供方业务拒绝（资源不存在/状态非法）走错误契约（200 + 错误体）→ 反序列化后
        // resourceId 缺省 → 响应校验失败抛错不 ack（绝不吞错照常 ack）
        when(recorder.alreadyConsumed(anyString(), eq("res-3"))).thenReturn(false);
        when(resourceStatusUpdateApi.updateResourceStatus(any()))
                .thenReturn(new ResourceStatusUpdateResponse());

        assertThrows(ServiceException.class, () -> service.consumeResourceStatusChanged(resourceEvent("res-3")));
        verify(recorder, never()).record(anyString(), any(EventEnvelope.class), anyString());
    }

    @Test
    void duplicateResourceEventShouldSkipWriteBack() {
        // 幂等检查在前：重复事件不重复回写
        when(recorder.alreadyConsumed(anyString(), eq("res-4"))).thenReturn(true);

        String outcome = service.consumeResourceStatusChanged(resourceEvent("res-4"));

        assertEquals(ConsumedEvent.OUTCOME_APPLIED, outcome);
        verify(resourceStatusUpdateApi, never()).updateResourceStatus(any());
        verify(recorder, never()).record(anyString(), any(EventEnvelope.class), anyString());
    }

    @Test
    void resourceEventShouldRejectMissingResourceIdOrToStatus() {
        when(recorder.alreadyConsumed(anyString(), anyString())).thenReturn(false);

        EventEnvelope noResourceId = resourceEvent("res-5");
        noResourceId.getPayload().remove("resourceId");
        assertThrows(ServiceException.class, () -> service.consumeResourceStatusChanged(noResourceId));

        EventEnvelope noToStatus = resourceEvent("res-6");
        noToStatus.getPayload().remove("toStatus");
        assertThrows(ServiceException.class, () -> service.consumeResourceStatusChanged(noToStatus));

        verify(resourceStatusUpdateApi, never()).updateResourceStatus(any());
        verify(recorder, never()).record(anyString(), any(EventEnvelope.class), anyString());
    }

    // ---- KD1 EXCEPTION 任务事件：复用既有 PAUSED 落库路径（零新逻辑）----

    @Test
    void exceptionTaskEventShouldReusePauseApplyPath() {
        when(recorder.alreadyConsumed(anyString(), eq("evt-exc"))).thenReturn(false);
        when(recorder.lastAppliedAt(anyString(), eq("7900000002"))).thenReturn(null);

        TestableConsumerService realApplyService = new TestableConsumerService(recorder, resourceStatusUpdateApi);
        realApplyService.stubApply = false;

        OperationTask task = new OperationTask();
        task.setTaskId(7900000002L);
        task.setBatchId(null);
        task.setStatus("RUNNING");

        try (MockedStatic<Db> db = mockStatic(Db.class)) {
            LambdaQueryChainWrapper queryWrapper = mock(LambdaQueryChainWrapper.class,
                    org.mockito.Mockito.withSettings().defaultAnswer(selfAnswer()));
            doReturn(task).when(queryWrapper).one();
            db.when(() -> Db.lambdaQuery(OperationTask.class)).thenReturn(queryWrapper);

            LambdaUpdateChainWrapper updateWrapper = mock(LambdaUpdateChainWrapper.class,
                    org.mockito.Mockito.withSettings().defaultAnswer(selfAnswer()));
            doReturn(true).when(updateWrapper).update();
            db.when(() -> Db.lambdaUpdate(OperationTask.class)).thenReturn(updateWrapper);

            EventEnvelope envelope = taskEvent("evt-exc", "2026-09-16T10:00:00+08:00", "7900000002");
            envelope.getPayload().put("eventType", "EXCEPTION");
            envelope.getPayload().put("targetStatus", "PAUSED");

            String outcome = realApplyService.consumeTaskStatusChanged(envelope);

            // EXCEPTION payload targetStatus=PAUSED → 既有无条件映射落库（无 eventType 分支，
            // 批次/订单行级联同款路径），零新逻辑
            assertEquals(ConsumedEvent.OUTCOME_APPLIED, outcome);
            verify(updateWrapper).set(any(), eq("PAUSED"));
            verify(updateWrapper).update();
        }
        verify(recorder).record(anyString(), any(EventEnvelope.class), eq(ConsumedEvent.OUTCOME_APPLIED));
    }

    /** 链式条件方法泛型擦除的统一自返回 Answer（同 master-data 契约单测套路）。 */
    private static org.mockito.stubbing.Answer<Object> selfAnswer() {
        return invocation -> {
            Class<?> returnType = invocation.getMethod().getReturnType();
            Object self = invocation.getMock();
            return returnType == Object.class || returnType.isInstance(self) ? self : null;
        };
    }
}
