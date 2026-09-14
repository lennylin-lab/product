package com.product.planning.controller;

import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.product.planning.api.PlanningBatchApi;
import com.product.planning.api.PlanningTaskApi;
import com.product.planning.api.dto.PlanningContracts;
import com.product.planning.common.constant.StatusConstants;
import com.product.planning.common.exception.ServiceException;
import com.product.planning.domain.entity.OperationTask;
import com.product.planning.domain.entity.ProductionBatch;
import com.product.planning.domain.entity.TaskAssignment;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 计划域内部契约端点（product-planning-api 的 PlanningBatchApi + PlanningTaskApi 实现，
 * Phase 4/5）。
 *
 * <p>消费方：product-demand（订单行详情 ProductionBatchView 填充、删除订单行时级联
 * 清理本域批次）；product-master-data（启用工艺路线删除保护的阻塞任务判断）；
 * product-execution（Phase 5：任务事件登记前的任务运行时只读查询——存在性/所属批次/
 * 派工机台，替代单体进程内跨模块查询）。</p>
 *
 * <p>鉴权与其余端点一致：调用方透传用户 JWT（或异步链路的 Identity 服务身份令牌），
 * 本地验签（ADR-0003 两层校验）；无有效 token 一律单体逐字节 401（防伪造内部头）。</p>
 *
 * <p>语义对齐（单体 demand 删行级联/单体裁剪）：</p>
 * <ul>
 *   <li>{@code batches/by-order-lines/delete}：与单体
 *       {@code delete from production_batch where order_line_id in (...)} 逐字对齐——
 *       只删 production_batch 行，不触碰 operation_task 等子表（该孤儿数据形态为单体
 *       冻结行为，迁移后保持一致）；</li>
 *   <li>{@code batches/has-blocking-tasks}：与单体 ProductRouteServiceImpl
 *       hasBlockingTasksForProduct 的末段一致——批次下存在 READY/SCHEDULED/RUNNING
 *       工序任务即视为阻塞；</li>
 *   <li>{@code tasks/runtime}（Phase 5）：taskId → {batchId, machineId}；machineId 取
 *       limit 1 的派工行 machine_id（与单体 TaskEventServiceImpl.loadMachineIdByTaskId
 *       同源语义，无派工为 null）；runtimes 仅包含存在的任务。</li>
 * </ul>
 */
@RestController
@RequestMapping("/internal/planning")
@RequiredArgsConstructor
public class InternalPlanningController implements PlanningBatchApi, PlanningTaskApi {

    /** 阻塞任务状态集合（与单体 BLOCKING_TASK_STATUSES 一致：READY/SCHEDULED/RUNNING）。 */
    private static final List<String> BLOCKING_TASK_STATUSES =
            List.of(StatusConstants.READY_OPERATION_TASK, StatusConstants.SCHEDULED_OPERATION_TASK,
                    StatusConstants.RUNNING_OPERATION_TASK);

    @Override
    public PlanningContracts.BatchesByOrderLinesResponse listBatchesByOrderLines(
            @RequestBody PlanningContracts.BatchesByOrderLinesRequest request) {
        List<Long> ids = requireIds(request);
        List<ProductionBatch> batches = Db.lambdaQuery(ProductionBatch.class)
                .in(ProductionBatch::getOrderLineId, ids)
                .list();
        PlanningContracts.BatchesByOrderLinesResponse response = new PlanningContracts.BatchesByOrderLinesResponse();
        response.setBatches(batches.stream()
                .filter(Objects::nonNull)
                .map(this::toViewDTO)
                .toList());
        return response;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlanningContracts.DeletedBatchesResponse deleteBatchesByOrderLines(
            @RequestBody PlanningContracts.BatchesByOrderLinesRequest request) {
        List<Long> ids = requireIds(request);
        long count = Db.lambdaQuery(ProductionBatch.class)
                .in(ProductionBatch::getOrderLineId, ids)
                .count();
        if (count > 0) {
            Db.lambdaUpdate(ProductionBatch.class)
                    .in(ProductionBatch::getOrderLineId, ids)
                    .remove();
        }
        return new PlanningContracts.DeletedBatchesResponse((int) count);
    }

    @Override
    public PlanningContracts.BlockingTasksResponse hasBlockingTasks(
            @RequestBody PlanningContracts.BatchesByOrderLinesRequest request) {
        List<Long> orderLineIds = requireIds(request);
        List<Long> batchIds = Db.lambdaQuery(ProductionBatch.class)
                .select(ProductionBatch::getBatchId)
                .in(ProductionBatch::getOrderLineId, orderLineIds)
                .list()
                .stream()
                .map(ProductionBatch::getBatchId)
                .filter(Objects::nonNull)
                .toList();
        if (batchIds.isEmpty()) {
            return new PlanningContracts.BlockingTasksResponse(false);
        }
        Long count = Db.lambdaQuery(OperationTask.class)
                .in(OperationTask::getBatchId, batchIds)
                .in(OperationTask::getStatus, BLOCKING_TASK_STATUSES)
                .count();
        return new PlanningContracts.BlockingTasksResponse(count != null && count > 0);
    }

    /** Phase 5：任务运行时批量只读查询（execution 任务事件登记前置校验用）。 */
    @Override
    public PlanningContracts.TaskRuntimeResponse taskRuntimes(
            @RequestBody PlanningContracts.TaskRuntimeRequest request) {
        List<Long> taskIds = requireTaskIds(request);
        // 派工主行（limit 1 语义与单体 TaskEventServiceImpl.loadMachineIdByTaskId 一致）：
        // 按 task_id 升序取首条派工行的 machine_id
        Map<Long, Long> machineByTask = new LinkedHashMap<>();
        if (!taskIds.isEmpty()) {
            Db.lambdaQuery(TaskAssignment.class)
                    .select(TaskAssignment::getTaskId, TaskAssignment::getMachineId)
                    .in(TaskAssignment::getTaskId, taskIds)
                    .orderByAsc(TaskAssignment::getAssignmentId)
                    .list()
                    .stream()
                    .filter(assignment -> assignment.getTaskId() != null)
                    .forEach(assignment -> machineByTask.putIfAbsent(assignment.getTaskId(),
                            assignment.getMachineId()));
        }
        PlanningContracts.TaskRuntimeResponse response = new PlanningContracts.TaskRuntimeResponse();
        response.setRuntimes(Db.lambdaQuery(OperationTask.class)
                .in(OperationTask::getTaskId, taskIds)
                .list()
                .stream()
                .filter(Objects::nonNull)
                .map(task -> {
                    PlanningContracts.TaskRuntimeDTO dto = new PlanningContracts.TaskRuntimeDTO();
                    dto.setTaskId(task.getTaskId());
                    dto.setBatchId(task.getBatchId());
                    dto.setMachineId(machineByTask.get(task.getTaskId()));
                    dto.setStatus(task.getStatus());
                    return dto;
                })
                .toList());
        return response;
    }

    private List<Long> requireTaskIds(PlanningContracts.TaskRuntimeRequest request) {
        if (request == null || request.getTaskIds() == null || request.getTaskIds().isEmpty()) {
            throw new ServiceException("任务ID集合不能为空");
        }
        List<Long> ids = request.getTaskIds();
        if (ids.size() > PlanningContracts.TaskRuntimeRequest.MAX_IDS) {
            throw new ServiceException("批量查询ID数超限: " + ids.size() + " > "
                    + PlanningContracts.TaskRuntimeRequest.MAX_IDS);
        }
        if (new LinkedHashSet<>(ids).size() != ids.size()) {
            throw new ServiceException("批量查询ID存在重复");
        }
        return ids;
    }

    private List<Long> requireIds(PlanningContracts.BatchesByOrderLinesRequest request) {
        if (request == null || request.getOrderLineIds() == null || request.getOrderLineIds().isEmpty()) {
            throw new ServiceException("订单行ID集合不能为空");
        }
        List<Long> ids = request.getOrderLineIds();
        if (ids.size() > PlanningContracts.BatchesByOrderLinesRequest.MAX_IDS) {
            throw new ServiceException("批量查询ID数超限: " + ids.size() + " > "
                    + PlanningContracts.BatchesByOrderLinesRequest.MAX_IDS);
        }
        if (new LinkedHashSet<>(ids).size() != ids.size()) {
            throw new ServiceException("批量查询ID存在重复");
        }
        return ids;
    }

    /** 批次行 → 只读视图（字段与 demand 侧 ProductionBatchView 查询列一致）。 */
    private PlanningContracts.ProductionBatchViewDTO toViewDTO(ProductionBatch batch) {
        PlanningContracts.ProductionBatchViewDTO dto = new PlanningContracts.ProductionBatchViewDTO();
        dto.setBatchId(batch.getBatchId());
        dto.setOrderLineId(batch.getOrderLineId());
        dto.setBatchQty(batch.getBatchQty());
        dto.setStatus(batch.getStatus());
        dto.setPlannedStart(batch.getPlannedStart());
        dto.setPlannedEnd(batch.getPlannedEnd());
        dto.setCreateTime(batch.getCreateTime());
        dto.setUpdateTime(batch.getUpdateTime());
        return dto;
    }
}
