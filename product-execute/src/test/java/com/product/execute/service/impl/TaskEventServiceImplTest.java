package com.product.execute.service.impl;

import com.product.common.constant.StatusConstants;
import com.product.common.constant.TaskEventConstants;
import com.product.domain.entity.TaskEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskEventServiceImplTest {

    @Test
    void startShouldUpdateStatusAndRecordEventWithMachineId() {
        RecordingTaskEventService service = new RecordingTaskEventService(true, 101L);

        boolean result = service.start(801L);

        assertTrue(result);
        assertEquals(801L, service.updatedTaskId);
        assertEquals(StatusConstants.RUNNING_OPERATION_TASK, service.updatedStatus);
        assertNotNull(service.savedEvent);
        assertEquals(TaskEventConstants.START_TASK_EVENT, service.savedEvent.getEventType());
        assertEquals(101L, service.savedEvent.getResourceId());
        assertEquals(601L, service.refreshedBatchId);
    }

    @Test
    void pauseShouldStillRecordEventWhenAssignmentMissing() {
        RecordingTaskEventService service = new RecordingTaskEventService(true, null, 602L);

        boolean result = service.pause(802L);

        assertTrue(result);
        assertEquals(StatusConstants.PAUSED_OPERATION_TASK, service.updatedStatus);
        assertNotNull(service.savedEvent);
        assertEquals(TaskEventConstants.PAUSE_TASK_EVENT, service.savedEvent.getEventType());
        assertNull(service.savedEvent.getResourceId());
        assertEquals(602L, service.refreshedBatchId);
    }

    @Test
    void resumeShouldReturnFalseWhenStatusUpdateFails() {
        RecordingTaskEventService service = new RecordingTaskEventService(false, 102L);

        boolean result = service.resume(803L);

        assertFalse(result);
        assertEquals(803L, service.updatedTaskId);
        assertEquals(StatusConstants.RUNNING_OPERATION_TASK, service.updatedStatus);
        assertNull(service.savedEvent);
        assertNull(service.refreshedBatchId);
    }

    @Test
    void completeShouldRejectEmptyTaskId() {
        RecordingTaskEventService service = new RecordingTaskEventService(true, 103L);

        boolean result = service.complete(null);

        assertFalse(result);
        assertNull(service.updatedTaskId);
        assertNull(service.savedEvent);
        assertNull(service.refreshedBatchId);
    }

    @Test
    void completeShouldReturnFalseWhenRefreshFails() {
        RecordingTaskEventService service = new RecordingTaskEventService(true, 104L, 604L);
        service.refreshResult = false;

        boolean result = service.complete(804L);

        assertFalse(result);
        assertNotNull(service.savedEvent);
        assertEquals(604L, service.refreshedBatchId);
    }

    private static final class RecordingTaskEventService extends TaskEventServiceImpl {

        private final boolean updateResult;
        private final Long machineId;
        private final Long batchId;
        private Long updatedTaskId;
        private String updatedStatus;
        private TaskEvent savedEvent;
        private Long refreshedBatchId;
        private boolean refreshResult = true;

        private RecordingTaskEventService(boolean updateResult, Long machineId) {
            this(updateResult, machineId, 601L);
        }

        private RecordingTaskEventService(boolean updateResult, Long machineId, Long batchId) {
            this.updateResult = updateResult;
            this.machineId = machineId;
            this.batchId = batchId;
        }

        @Override
        protected boolean updateTaskStatus(Long taskId, String targetStatus) {
            this.updatedTaskId = taskId;
            this.updatedStatus = targetStatus;
            return updateResult;
        }

        @Override
        protected Long loadMachineIdByTaskId(Long taskId) {
            return machineId;
        }

        @Override
        protected Long loadBatchIdByTaskId(Long taskId) {
            return batchId;
        }

        @Override
        protected boolean refreshRelatedBusinessStatus(Long taskId) {
            this.refreshedBatchId = loadBatchIdByTaskId(taskId);
            return refreshResult;
        }

        @Override
        public boolean save(TaskEvent entity) {
            this.savedEvent = entity;
            return true;
        }
    }
}
