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
        RecordingTaskEventService service = new RecordingTaskEventService(true, "MC-01");

        boolean result = service.start("TASK-001");

        assertTrue(result);
        assertEquals("TASK-001", service.updatedTaskId);
        assertEquals(StatusConstants.RUNNING_OPERATION_TASK, service.updatedStatus);
        assertNotNull(service.savedEvent);
        assertEquals(TaskEventConstants.START_TASK_EVENT, service.savedEvent.getEventType());
        assertEquals("MC-01", service.savedEvent.getResourceId());
    }

    @Test
    void pauseShouldStillRecordEventWhenAssignmentMissing() {
        RecordingTaskEventService service = new RecordingTaskEventService(true, null);

        boolean result = service.pause("TASK-002");

        assertTrue(result);
        assertEquals(StatusConstants.PAUSED_OPERATION_TASK, service.updatedStatus);
        assertNotNull(service.savedEvent);
        assertEquals(TaskEventConstants.PAUSE_TASK_EVENT, service.savedEvent.getEventType());
        assertNull(service.savedEvent.getResourceId());
    }

    @Test
    void resumeShouldReturnFalseWhenStatusUpdateFails() {
        RecordingTaskEventService service = new RecordingTaskEventService(false, "MC-02");

        boolean result = service.resume("TASK-003");

        assertFalse(result);
        assertEquals("TASK-003", service.updatedTaskId);
        assertEquals(StatusConstants.RUNNING_OPERATION_TASK, service.updatedStatus);
        assertNull(service.savedEvent);
    }

    @Test
    void completeShouldRejectEmptyTaskId() {
        RecordingTaskEventService service = new RecordingTaskEventService(true, "MC-03");

        boolean result = service.complete("");

        assertFalse(result);
        assertNull(service.updatedTaskId);
        assertNull(service.savedEvent);
    }

    private static final class RecordingTaskEventService extends TaskEventServiceImpl {

        private final boolean updateResult;
        private final String machineId;
        private String updatedTaskId;
        private String updatedStatus;
        private TaskEvent savedEvent;

        private RecordingTaskEventService(boolean updateResult, String machineId) {
            this.updateResult = updateResult;
            this.machineId = machineId;
        }

        @Override
        protected boolean updateTaskStatus(String taskId, String targetStatus) {
            this.updatedTaskId = taskId;
            this.updatedStatus = targetStatus;
            return updateResult;
        }

        @Override
        protected String loadMachineIdByTaskId(String taskId) {
            return machineId;
        }

        @Override
        public boolean save(TaskEvent entity) {
            this.savedEvent = entity;
            return true;
        }
    }
}
