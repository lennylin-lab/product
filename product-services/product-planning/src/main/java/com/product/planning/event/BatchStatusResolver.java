package com.product.planning.event;

import com.product.planning.common.constant.StatusConstants;

import java.util.List;
import java.util.Objects;

/**
 * 批次状态聚合推导（单体 product-pps ProductionBatchStatusRefresher.resolveBatchStatus
 * 的服务化移植，状态机语义冻结）：
 *
 * <pre>
 *   DONE_PRODUCTION_BATCH       ← 所有工序任务均为 DONE
 *   IN_PROCESS_PRODUCTION_BATCH ← 存在 RUNNING/PAUSED/DONE 状态的工序任务
 *   RELEASED_PRODUCTION_BATCH   ← 存在 SCHEDULED/READY 状态的工序任务
 *   其余保持 currentStatus
 * </pre>
 */
public final class BatchStatusResolver {

    private BatchStatusResolver() {
    }

    /** 聚合推导生产批次目标状态：全完成→DONE，存在执行中→IN_PROCESS，存在已释放→RELEASED。 */
    public static String resolveBatchStatus(List<String> taskStatuses, String currentStatus) {
        if (taskStatuses.stream().allMatch(StatusConstants.DONE_OPERATION_TASK::equals)) {
            return StatusConstants.DONE_PRODUCTION_BATCH;
        }
        boolean hasInProcess = taskStatuses.stream().anyMatch(status ->
                StatusConstants.RUNNING_OPERATION_TASK.equals(status)
                        || StatusConstants.PAUSED_OPERATION_TASK.equals(status)
                        || StatusConstants.DONE_OPERATION_TASK.equals(status));
        if (hasInProcess) {
            return StatusConstants.IN_PROCESS_PRODUCTION_BATCH;
        }
        boolean hasReleasedTasks = taskStatuses.stream().anyMatch(status ->
                StatusConstants.SCHEDULED_OPERATION_TASK.equals(status)
                        || StatusConstants.READY_OPERATION_TASK.equals(status));
        if (hasReleasedTasks) {
            return StatusConstants.RELEASED_PRODUCTION_BATCH;
        }
        return currentStatus;
    }

    /** 判等（null 安全，语义与单体 Objects.equals 落库守卫一致）。 */
    public static boolean differs(String target, String current) {
        return !Objects.equals(target, current);
    }
}
