package com.product.pps.service.impl;

import com.product.common.constant.StatusConstants;
import com.product.domain.entity.OperationTask;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskSchedulingQueryServiceTest {

    private final TaskSchedulingQueryService queryService = new TaskSchedulingQueryService();

    @Test
    void normalizeReadyTasksForSchedulingShouldFilterAndSortTasksDeterministically() {
        OperationTask laterTask = buildTask("T-LATE", "B2", 2L,
                LocalDateTime.of(2026, 4, 21, 10, 0), StatusConstants.READY_OPERATION_TASK);
        OperationTask earliestTask = buildTask("T-EARLY", "B1", 2L,
                LocalDateTime.of(2026, 4, 21, 8, 0), StatusConstants.READY_OPERATION_TASK);
        OperationTask sameTimeLowerSequence = buildTask("T-SEQ-1", "B1", 1L,
                LocalDateTime.of(2026, 4, 21, 8, 0), StatusConstants.READY_OPERATION_TASK);
        OperationTask duplicateTask = buildTask("T-SEQ-1", "B9", 9L,
                LocalDateTime.of(2026, 4, 21, 12, 0), StatusConstants.READY_OPERATION_TASK);
        OperationTask nonReadyTask = buildTask("T-NOT-READY", "B3", 1L,
                LocalDateTime.of(2026, 4, 21, 7, 0), StatusConstants.SCHEDULED_OPERATION_TASK);
        OperationTask emptyIdTask = buildTask("", "B4", 1L,
                LocalDateTime.of(2026, 4, 21, 9, 0), StatusConstants.READY_OPERATION_TASK);

        List<OperationTask> result = queryService.normalizeReadyTasksForScheduling(
                List.of(laterTask, earliestTask, sameTimeLowerSequence, duplicateTask, nonReadyTask, emptyIdTask),
                Set.of("T-LATE"));

        assertEquals(List.of("T-SEQ-1", "T-EARLY"),
                result.stream().map(OperationTask::getTaskId).toList());
    }

    @Test
    void normalizeReadyTasksForSchedulingShouldReturnEmptyWhenInputInvalid() {
        OperationTask assignedTask = buildTask("T-1", "B1", 1L,
                LocalDateTime.of(2026, 4, 21, 8, 0), StatusConstants.READY_OPERATION_TASK);
        OperationTask nullStartTask = buildTask("T-2", "B1", 2L, null, StatusConstants.READY_OPERATION_TASK);

        List<OperationTask> result = queryService.normalizeReadyTasksForScheduling(
                Arrays.asList(assignedTask, null, nullStartTask),
                Set.of("T-1", "T-2"));

        assertTrue(result.isEmpty());
    }

    private OperationTask buildTask(String taskId,
                                    String batchId,
                                    Long sequence,
                                    LocalDateTime earliestStart,
                                    String status) {
        OperationTask task = new OperationTask();
        task.setTaskId(taskId);
        task.setBatchId(batchId);
        task.setSequence(sequence);
        task.setEarliestStart(earliestStart);
        task.setStatus(status);
        return task;
    }
}
