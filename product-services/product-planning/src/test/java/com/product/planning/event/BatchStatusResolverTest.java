package com.product.planning.event;

import com.product.planning.common.constant.StatusConstants;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 批次状态聚合规则测试（单体 ProductionBatchStatusRefresher.resolveBatchStatus 冻结语义）。
 */
class BatchStatusResolverTest {

    @Test
    void allTasksDoneShouldResolveBatchDone() {
        assertEquals(StatusConstants.DONE_PRODUCTION_BATCH, BatchStatusResolver.resolveBatchStatus(
                List.of(StatusConstants.DONE_OPERATION_TASK, StatusConstants.DONE_OPERATION_TASK),
                StatusConstants.IN_PROCESS_PRODUCTION_BATCH));
    }

    @Test
    void anyRunningPausedOrDoneShouldResolveInProcess() {
        assertEquals(StatusConstants.IN_PROCESS_PRODUCTION_BATCH, BatchStatusResolver.resolveBatchStatus(
                List.of(StatusConstants.RUNNING_OPERATION_TASK, StatusConstants.SCHEDULED_OPERATION_TASK),
                StatusConstants.RELEASED_PRODUCTION_BATCH));
        assertEquals(StatusConstants.IN_PROCESS_PRODUCTION_BATCH, BatchStatusResolver.resolveBatchStatus(
                List.of(StatusConstants.PAUSED_OPERATION_TASK), StatusConstants.RELEASED_PRODUCTION_BATCH));
        // 单体冻结语义：DONE 与 SCHEDULED 并存仍为 IN_PROCESS（非全 DONE）
        assertEquals(StatusConstants.IN_PROCESS_PRODUCTION_BATCH, BatchStatusResolver.resolveBatchStatus(
                List.of(StatusConstants.DONE_OPERATION_TASK, StatusConstants.SCHEDULED_OPERATION_TASK),
                StatusConstants.RELEASED_PRODUCTION_BATCH));
    }

    @Test
    void anyScheduledOrReadyShouldResolveReleased() {
        assertEquals(StatusConstants.RELEASED_PRODUCTION_BATCH, BatchStatusResolver.resolveBatchStatus(
                List.of(StatusConstants.SCHEDULED_OPERATION_TASK, StatusConstants.READY_OPERATION_TASK),
                StatusConstants.PLANNED_PRODUCTION_BATCH));
    }

    @Test
    void unknownStatusesShouldKeepCurrentStatus() {
        assertEquals(StatusConstants.PLANNED_PRODUCTION_BATCH, BatchStatusResolver.resolveBatchStatus(
                List.of("WEIRD"), StatusConstants.PLANNED_PRODUCTION_BATCH));
    }
}
