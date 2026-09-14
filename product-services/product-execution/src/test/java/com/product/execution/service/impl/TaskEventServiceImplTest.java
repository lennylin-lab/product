package com.product.execution.service.impl;

import com.product.cloud.messaging.api.EventEnvelope;
import com.product.cloud.messaging.api.EventTypes;
import com.product.execution.common.constant.StatusConstants;
import com.product.execution.common.constant.TaskEventConstants;
import com.product.execution.domain.entity.TaskEvent;
import com.product.planning.api.PlanningTaskApi;
import com.product.planning.api.dto.PlanningContracts;
import org.junit.jupiter.api.Test;

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
                                                PlanningContracts.TaskRuntimeDTO runtime, Long occurredEventId) {
            EventEnvelope envelope = buildTaskStatusEnvelope(taskId, eventType, targetStatus,
                    loadMachineIdByTaskId(runtime), occurredEventId);
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
