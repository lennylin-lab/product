package com.product.pps.status;

import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.product.common.constant.StatusConstants;
import com.product.core.status.StatusEntityType;
import com.product.core.status.StatusRefreshContext;
import com.product.core.status.StatusRefresher;
import com.product.domain.entity.OperationTask;
import com.product.domain.entity.ProductionBatch;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 生产批次状态刷新器。
 *
 * <p>根据关联的 {@link OperationTask} 状态聚合推导生产批次状态，状态流转方向为单向递进：</p>
 * <pre>
 *   RELEASED_PRODUCTION_BATCH ← 存在 SCHEDULED/READY 状态的工序任务
 *   IN_PROCESS_PRODUCTION_BATCH ← 存在 RUNNING/PAUSED/DONE 状态的工序任务
 *   DONE_PRODUCTION_BATCH ← 所有工序任务均为 DONE
 * </pre>
 *
 * <p>触发场景：工序任务状态变更（开始、暂停、恢复、完成）后，由 {@link com.product.core.status.StatusRefreshService} 级联调用。</p>
 */
@Component
public class ProductionBatchStatusRefresher implements StatusRefresher {

    @Override
    public int getOrder() {
        return StatusEntityType.PRODUCTION_BATCH.getOrder();
    }

    @Override
    public void refresh(StatusRefreshContext context) {
        if (context == null || context.getEntityType() != StatusEntityType.PRODUCTION_BATCH) {
            return;
        }
        for (Object id : context.getIds()) {
            if (id == null) {
                continue;
            }
            refreshBatch(String.valueOf(id));
        }
    }

    /** 刷新单个生产批次状态：汇总其下所有工序任务状态，仅在目标状态与当前不同时才写入 */
    private void refreshBatch(String batchId) {
        ProductionBatch batch = Db.lambdaQuery(ProductionBatch.class)
                .select(ProductionBatch::getBatchId, ProductionBatch::getStatus)
                .eq(ProductionBatch::getBatchId, batchId)
                .last("limit 1")
                .one();
        if (batch == null) {
            return;
        }
        List<String> taskStatuses = Db.lambdaQuery(OperationTask.class)
                .select(OperationTask::getStatus)
                .eq(OperationTask::getBatchId, batchId)
                .list()
                .stream()
                .filter(Objects::nonNull)
                .map(OperationTask::getStatus)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (CollectionUtils.isEmpty(taskStatuses)) {
            return;
        }
        String targetStatus = resolveBatchStatus(taskStatuses, batch.getStatus());
        if (!Objects.equals(batch.getStatus(), targetStatus)) {
            Db.lambdaUpdate(ProductionBatch.class)
                    .set(ProductionBatch::getStatus, targetStatus)
                    .eq(ProductionBatch::getBatchId, batchId)
                    .update();
        }
    }

    /** 聚合推导生产批次目标状态：全完成→DONE，存在执行中→IN_PROCESS，存在已释放→RELEASED */
    private String resolveBatchStatus(List<String> taskStatuses, String currentStatus) {
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
}
