package com.product.execution.service.impl;

import com.product.cloud.messaging.api.EventEnvelope;
import com.product.cloud.messaging.api.EventTypes;
import com.product.execution.common.constant.StatusConstants;
import com.product.execution.common.constant.TaskEventConstants;
import com.product.execution.domain.entity.TaskEvent;
import com.product.planning.api.PlanningTaskApi;
import com.product.planning.api.dto.PlanningContracts;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 任务事件服务测试（移植单体 product-execute TaskEventServiceImplTest 的 5 条语义 +
 * Phase 5 事件链新增断言：事件发布 payload、任务不存在/契约不可达 fail-closed）。
 */
class TaskEventServiceImplTest {

    @Test
    void startShouldRecordEventWithMachineIdAndPublishEvent() {
        RecordingTaskEventService service = new RecordingTaskEventService(true, 101L);

        boolean result = service.start(801L);

        assertTrue(result);
        assertEquals(801L, service.taskRuntimeQueried);
        assertNotNull(service.savedEvent);
        assertEquals(TaskEventConstants.START_TASK_EVENT, service.savedEvent.getEventType());
        assertEquals(101L, service.savedEvent.getResourceId());
        // Phase 5：发布 task.status.changed（目标 RUNNING）
        assertEquals(1, service.published.size());
        Published published = service.published.poll();
        assertEquals(EventTypes.TASK_STATUS_CHANGED, published.envelope.getEventType());
        assertEquals("801", published.envelope.getAggregateId());
        assertEquals(801L, published.payload.get("taskId"));
        assertEquals(TaskEventConstants.START_TASK_EVENT, published.payload.get("eventType"));
        assertEquals(StatusConstants.RUNNING_OPERATION_TASK, published.payload.get("targetStatus"));
        assertEquals(101L, published.payload.get("resourceId"));
        assertEquals(9002L, published.payload.get("occurredEventId"));
    }

    @Test
    void pauseShouldStillRecordEventWhenAssignmentMissing() {
        RecordingTaskEventService service = new RecordingTaskEventService(true, null);

        boolean result = service.pause(802L);

        assertTrue(result);
        assertNotNull(service.savedEvent);
        assertEquals(TaskEventConstants.PAUSE_TASK_EVENT, service.savedEvent.getEventType());
        assertNull(service.savedEvent.getResourceId());
        assertEquals(1, service.published.size());
        Published published = service.published.poll();
        assertEquals(StatusConstants.PAUSED_OPERATION_TASK, published.payload.get("targetStatus"));
        assertTrue(published.payload.containsKey("resourceId"));
        assertNull(published.payload.get("resourceId"));
    }

    @Test
    void resumeShouldReturnFalseWhenTaskUnknown() {
        // Phase 5 语义（与单体 update 影响 0 行等价）：planning 契约查无任务 → false，无事件
        RecordingTaskEventService service = new RecordingTaskEventService(false, 102L);

        boolean result = service.resume(803L);

        assertFalse(result);
        assertNull(service.savedEvent);
        assertTrue(service.published.isEmpty());
    }

    @Test
    void completeShouldRejectEmptyTaskId() {
        RecordingTaskEventService service = new RecordingTaskEventService(true, 103L);

        boolean result = service.complete(null);

        assertFalse(result);
        assertNull(service.savedEvent);
        assertTrue(service.published.isEmpty());
        // 空任务ID不应触发契约调用
        assertNull(service.taskRuntimeQueried);
    }

    @Test
    void completeShouldReturnFalseWhenEventSaveFails() {
        RecordingTaskEventService service = new RecordingTaskEventService(true, 104L);
        service.saveResult = false;

        boolean result = service.complete(804L);

        assertFalse(result);
        // 保存失败不发布事件（同事务回滚语义在命令层由 @Transactional 保证）
        assertTrue(service.published.isEmpty());
    }

    @Test
    void completeShouldFailClosedWhenRuntimeContractUnavailable() {
        // 契约不可达（Feign 抛异常）→ 真实 loadTaskRuntime 捕获后返回 null → fail-closed false
        // （与单体同库不可达即失败等价），不静默放行
        RecordingTaskEventService service = new RecordingTaskEventService(true, 105L, false);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "planningTaskApi",
                new PlanningTaskApi() {
                    @Override
                    public PlanningContracts.TaskRuntimeResponse taskRuntimes(
                            PlanningContracts.TaskRuntimeRequest request) {
                        throw new IllegalStateException("connect refused");
                    }
                });

        boolean result = service.complete(805L);

        assertFalse(result);
        assertNull(service.savedEvent);
        assertTrue(service.published.isEmpty());
    }

    @Test
    void envelopeBuilderShouldCarryMonolithFaithfulPayload() {
        RecordingTaskEventService service = new RecordingTaskEventService(true, 101L);
        EventEnvelope envelope = service.buildTaskStatusEnvelope(9001L,
                TaskEventConstants.FINISH_TASK_EVENT, StatusConstants.DONE_OPERATION_TASK, 77L, 9002L);
        assertEquals(EventTypes.TASK_STATUS_CHANGED, envelope.getEventType());
        assertEquals("9001", envelope.getAggregateId());
        assertEquals(1, envelope.getVersion());
        assertNotNull(envelope.getOccurredAt());
        assertNotNull(envelope.getCorrelationId());
        assertEquals(9001L, envelope.getPayload().get("taskId"));
        assertEquals("FINISH", envelope.getPayload().get("eventType"));
        assertEquals("DONE", envelope.getPayload().get("targetStatus"));
        assertEquals(77L, envelope.getPayload().get("resourceId"));
        assertEquals(9002L, envelope.getPayload().get("occurredEventId"));
    }

    @Test
    void insertTaskEventShouldRejectInvalidTaskId() {
        RecordingTaskEventService service = new RecordingTaskEventService(true, 106L);

        for (Long badTaskId : new Long[] {null, 0L, -5L}) {
            TaskEvent event = new TaskEvent();
            event.setTaskId(badTaskId);
            event.setEventType(TaskEventConstants.START_TASK_EVENT);
            org.junit.jupiter.api.Assertions.assertThrows(
                    com.product.execution.common.exception.ServiceException.class,
                    () -> service.insertTaskEvent(event),
                    "taskId=" + badTaskId + " 应被拒绝");
        }
        // 非法 taskId 不应触发契约调用，也不应落库
        assertNull(service.taskRuntimeQueried);
        assertNull(service.savedEvent);
    }

    @Test
    void insertTaskEventShouldRejectUnknownTask() {
        RecordingTaskEventService service = new RecordingTaskEventService(false, 107L);

        TaskEvent event = new TaskEvent();
        event.setTaskId(9999L);
        event.setEventType(TaskEventConstants.START_TASK_EVENT);

        org.junit.jupiter.api.Assertions.assertThrows(
                com.product.execution.common.exception.ServiceException.class,
                () -> service.insertTaskEvent(event));

        assertEquals(9999L, service.taskRuntimeQueried);
        assertNull(service.savedEvent);
    }

    @Test
    void insertTaskEventShouldDefaultEventTimeWhenMissing() {
        RecordingTaskEventService service = new RecordingTaskEventService(true, 108L);

        TaskEvent event = new TaskEvent();
        event.setTaskId(806L);
        event.setEventType(TaskEventConstants.START_TASK_EVENT);
        event.setEventTime(null);

        assertTrue(service.insertTaskEvent(event));
        assertNotNull(service.savedEvent.getEventTime());
        // 直录不发布状态事件（与 record() 命令链不同，仅落追溯行）
        assertTrue(service.published.isEmpty());
    }

    @Test
    void insertTaskEventShouldKeepProvidedEventTime() {
        RecordingTaskEventService service = new RecordingTaskEventService(true, 109L);

        TaskEvent event = new TaskEvent();
        event.setTaskId(807L);
        event.setEventType(TaskEventConstants.START_TASK_EVENT);
        event.setEventTime(LocalDateTime.of(2026, 9, 15, 8, 0));

        assertTrue(service.insertTaskEvent(event));
        assertEquals(LocalDateTime.of(2026, 9, 15, 8, 0), service.savedEvent.getEventTime());
    }

    // ---- KD1 异常事件建模（2026-09-16 增量）----

    @Test
    void exceptionShouldPauseTaskRecordReasonAndPublishPayloadReasonCode() {
        RecordingTaskEventService service = new RecordingTaskEventService(true, 101L);

        boolean result = service.exception(811L, "EQUIP_FAULT", "机台故障停机");

        assertTrue(result);
        assertEquals(811L, service.taskRuntimeQueried);
        assertNotNull(service.savedEvent);
        // KD1：目标状态复用 PAUSED（无新状态值）；事件行落 reason_code/remark
        assertEquals(TaskEventConstants.EXCEPTION_TASK_EVENT, service.savedEvent.getEventType());
        assertEquals("EQUIP_FAULT", service.savedEvent.getReasonCode());
        assertEquals("机台故障停机", service.savedEvent.getRemark());
        assertEquals(101L, service.savedEvent.getResourceId());
        // payload 带 reasonCode；目标 PAUSED（消费侧复用既有暂停级联，零新逻辑）
        assertEquals(1, service.published.size());
        Published published = service.published.poll();
        assertEquals(EventTypes.TASK_STATUS_CHANGED, published.envelope.getEventType());
        assertEquals("811", published.envelope.getAggregateId());
        assertEquals(TaskEventConstants.EXCEPTION_TASK_EVENT, published.payload.get("eventType"));
        assertEquals(StatusConstants.PAUSED_OPERATION_TASK, published.payload.get("targetStatus"));
        assertEquals("EQUIP_FAULT", published.payload.get("reasonCode"));
    }

    @Test
    void exceptionShouldRejectBlankReasonCode() {
        RecordingTaskEventService service = new RecordingTaskEventService(true, 110L);

        for (String badReason : new String[] {null, "", "   "}) {
            org.junit.jupiter.api.Assertions.assertThrows(
                    com.product.execution.common.exception.ServiceException.class,
                    () -> service.exception(812L, badReason, null),
                    "reasonCode=" + badReason + " 应被拒绝");
        }
        // 空白原因码不应触发契约调用，也不落库/发布
        assertNull(service.taskRuntimeQueried);
        assertNull(service.savedEvent);
        assertTrue(service.published.isEmpty());
    }

    @Test
    void exceptionShouldReturnFalseWhenTaskUnknown() {
        // 任务不存在 → 既有「update 影响 0 行」失败语义（与四事件一致），无事件行/无发布
        RecordingTaskEventService service = new RecordingTaskEventService(false, 111L);

        boolean result = service.exception(813L, "EQUIP_FAULT", null);

        assertFalse(result);
        assertNull(service.savedEvent);
        assertTrue(service.published.isEmpty());
    }

    @Test
    void frozenFourEventsShouldNotCarryReasonCodeKeyInPayload() {
        // 事件 schema 只加不改回归：四类既有事件 payload 不含 reasonCode 键
        // （与迁移前逐字节一致），事件行 reason_code/remark 不落值
        RecordingTaskEventService service = new RecordingTaskEventService(true, 101L);

        assertTrue(service.start(821L));
        assertTrue(service.pause(822L));
        assertTrue(service.resume(823L));
        assertTrue(service.complete(824L));

        assertEquals(4, service.published.size());
        for (Published published : service.published) {
            assertFalse(published.payload().containsKey("reasonCode"),
                    "既有事件 payload 不应出现 reasonCode 键: " + published.payload().get("eventType"));
        }
    }

    @Test
    void envelopeBuilderShouldCarryOptionalReasonCodeOnlyWhenPresent() {
        RecordingTaskEventService service = new RecordingTaskEventService(true, 101L);

        EventEnvelope withReason = service.buildTaskStatusEnvelope(831L,
                TaskEventConstants.EXCEPTION_TASK_EVENT, StatusConstants.PAUSED_OPERATION_TASK, 77L, 9002L,
                "EQUIP_FAULT");
        assertEquals(1, withReason.getVersion());
        assertEquals("EQUIP_FAULT", withReason.getPayload().get("reasonCode"));
        assertTrue(withReason.getPayload().containsKey("reasonCode"));

        EventEnvelope withoutReason = service.buildTaskStatusEnvelope(832L,
                TaskEventConstants.PAUSE_TASK_EVENT, StatusConstants.PAUSED_OPERATION_TASK, 77L, 9003L, null);
        assertFalse(withoutReason.getPayload().containsKey("reasonCode"));
    }

    @Test
    void insertTaskEventShouldAcceptExceptionTypeForDirectRecord() {
        // 直录路径（POST /execute/event）接受 eventType=EXCEPTION（沿用 issue#4 直录校验，
        // 仅落追溯行、不发布状态事件）
        RecordingTaskEventService service = new RecordingTaskEventService(true, 112L);

        TaskEvent event = new TaskEvent();
        event.setTaskId(814L);
        event.setEventType(TaskEventConstants.EXCEPTION_TASK_EVENT);
        event.setReasonCode("MATERIAL_SHORT");

        assertTrue(service.insertTaskEvent(event));
        assertEquals(TaskEventConstants.EXCEPTION_TASK_EVENT, service.savedEvent.getEventType());
        assertEquals("MATERIAL_SHORT", service.savedEvent.getReasonCode());
        assertTrue(service.published.isEmpty());
    }


    private record Published(EventEnvelope envelope, Map<String, Object> payload) {
    }

    /**
     * 记录桩（移植单体 RecordingTaskEventService 模式：覆写契约查询/保存/发布边界）。
     * overrideRuntime=false 时保留真实 loadTaskRuntime（用于契约异常捕获路径的测试）。
     */
    private static final class RecordingTaskEventService extends TaskEventServiceImpl {

        private final boolean runtimeFound;
        private final Long machineId;
        private final boolean overrideRuntime;
        private Long taskRuntimeQueried;
        private TaskEvent savedEvent;
        private boolean saveResult = true;
        private final ConcurrentLinkedQueue<Published> published = new ConcurrentLinkedQueue<>();

        private RecordingTaskEventService(boolean runtimeFound, Long machineId) {
            this(runtimeFound, machineId, true);
        }

        private RecordingTaskEventService(boolean runtimeFound, Long machineId, boolean overrideRuntime) {
            this.runtimeFound = runtimeFound;
            this.machineId = machineId;
            this.overrideRuntime = overrideRuntime;
        }

        @Override
        protected PlanningContracts.TaskRuntimeDTO loadTaskRuntime(Long taskId) {
            if (!overrideRuntime) {
                return super.loadTaskRuntime(taskId);
            }
            taskRuntimeQueried = taskId;
            if (!runtimeFound) {
                return null;
            }
            PlanningContracts.TaskRuntimeDTO dto = new PlanningContracts.TaskRuntimeDTO();
            dto.setTaskId(taskId);
            dto.setBatchId(601L);
            dto.setMachineId(machineId);
            dto.setStatus("SCHEDULED");
            return dto;
        }

        @Override
        protected Long loadMachineIdByTaskId(PlanningContracts.TaskRuntimeDTO runtime) {
            return machineId;
        }

        @Override
        protected void publishTaskStatusChanged(Long taskId, String eventType, String targetStatus,
                                                PlanningContracts.TaskRuntimeDTO runtime, Long occurredEventId,
                                                String reasonCode) {
            EventEnvelope envelope = buildTaskStatusEnvelope(taskId, eventType, targetStatus,
                    loadMachineIdByTaskId(runtime), occurredEventId, reasonCode);
            published.add(new Published(envelope, envelope.getPayload()));
        }

        @Override
        public boolean save(TaskEvent entity) {
            // IdWorker.getId() 的离线替代：固定事件行 ID，供 occurredEventId 断言
            entity.setEventId(9002L);
            this.savedEvent = entity;
            return saveResult;
        }
    }
}
