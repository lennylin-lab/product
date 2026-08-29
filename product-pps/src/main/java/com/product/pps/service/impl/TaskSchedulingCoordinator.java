package com.product.pps.service.impl;

import com.product.common.utils.StringUtils;
import com.product.domain.dto.TaskAssignmentDTO;
import com.product.domain.entity.Resource;
import com.product.pps.dto.ScheduleExecutionResult;
import com.product.pps.dto.ScheduleProgressDTO;
import com.product.pps.dto.TaskSchedulingPriorityDTO;
import com.product.pps.enums.SchedulePhase;
import com.product.pps.enums.SchedulingStrategy;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 排程协调器。
 *
 * 只负责编排排程流程，不承载具体算法与持久化细节：
 * - 读取排程基础数据
 * - 分批计算任务分配
 * - 统一提交落库
 * - 回传进度与耗时统计
 */
@Slf4j
@Service
public class TaskSchedulingCoordinator {
    @Value("${product.pps.schedule.batch-size:200}")
    private int scheduleBatchSize;

    /**
     * 进度推送阈值，单位为任务数。
     */
    @Value("${product.pps.schedule.progress-push-task-step:50}")
    private int progressPushTaskStep;

    @Autowired
    private TaskSchedulingQueryService taskSchedulingQueryService;
    @Autowired
    private TaskSchedulingCalculator taskSchedulingCalculator;
    @Autowired
    private TaskAssignmentPersistenceService taskAssignmentPersistenceService;
    @Autowired
    @Qualifier("threadPoolTaskExecutor")
    private ThreadPoolTaskExecutor threadPoolTaskExecutor;
    @Autowired
    private TransactionTemplate transactionTemplate;

    /**
     * 执行全量排程计划。
     */
    public ScheduleExecutionResult executeSchedulePlan(TaskAssignmentDTO taskAssignmentDTO) {
        return executeSchedulePlan(taskAssignmentDTO, null);
    }

    /**
     * 执行全量排程计划，并在计算过程中回传进度快照。
     */
    public ScheduleExecutionResult executeSchedulePlan(TaskAssignmentDTO taskAssignmentDTO,
                                                       Consumer<ScheduleProgressDTO> progressConsumer) {
        return runScheduleAll(taskAssignmentDTO, progressConsumer);
    }

    /**
     * 排程核心编排：仅负责流程调度，计算委托 Calculator，持久化委托 PersistenceService。
     *
     * @param taskAssignmentDTO    排程参数（可指定排程开始时间）
     * @param progressConsumer     进度回调（可为 null）
     * @return 排程执行结果
     */
    private ScheduleExecutionResult runScheduleAll(TaskAssignmentDTO taskAssignmentDTO, Consumer<ScheduleProgressDTO> progressConsumer) {
        long totalStart = System.currentTimeMillis();
        SchedulingStrategy strategy = SchedulingStrategy.fromCode(taskAssignmentDTO == null ? null : taskAssignmentDTO.getScheduleStrategy());

        // 阶段1: 准备 -- 获取排程基准时间、待排任务列表、完整资源上下文（MACHINE+MOLD+PERSON+Calendar）
        LocalDateTime assignmentStart = resolveAssignmentStart(taskAssignmentDTO);
        CompletableFuture<List<Resource>> machinesFuture = CompletableFuture.supplyAsync(
                taskSchedulingQueryService::loadAvailableMachines,
                threadPoolTaskExecutor);
        CompletableFuture<List<com.product.domain.entity.OperationTask>> readyTasksFuture = CompletableFuture.supplyAsync(
                taskSchedulingQueryService::loadReadyTaskList,
                threadPoolTaskExecutor);

        // 先获取机台列表（用于快速判断是否有可用资源）
        List<Resource> machines = machinesFuture.join();
        if (CollectionUtils.isEmpty(machines)) {
            return ScheduleExecutionResult.failure("没有可用机台");
        }

        // 获取待排任务列表
        List<com.product.domain.entity.OperationTask> readyTasks = readyTasksFuture.join();
        int totalTaskCount = readyTasks == null ? 0 : readyTasks.size();
        if (totalTaskCount == 0) {
            notifyScheduleProgress(progressConsumer, 0, 0, 0, 100, SchedulePhase.NO_TASK);
            return ScheduleExecutionResult.success(0, 0);
        }
        notifyScheduleProgress(progressConsumer, totalTaskCount, 0, 0, 0, SchedulePhase.STARTED);

        // 加载完整资源上下文（包含 MACHINE/MOLD/PERSON + 日历映射）
        TaskSchedulingQueryService.SchedulingResourceContext schedulingContext =
                taskSchedulingQueryService.loadSchedulingResourceContext(readyTasks);

        // 构建多资源运行时上下文（内存快照）
        TaskSchedulingCalculator.ResourceRuntimeContext runtimeContext =
                taskSchedulingCalculator.buildResourceRuntimeContext(schedulingContext);

        Map<String, TaskSchedulingPriorityDTO> priorityMap = SchedulingStrategy.DUE_DATE_PRIORITY == strategy
                ? taskSchedulingQueryService.loadTaskPriorityMap(readyTasks)
                : Map.of();
        List<com.product.domain.entity.OperationTask> orderedTasks =
                taskSchedulingCalculator.orderTasks(readyTasks, strategy, priorityMap);

        List<String> schedulableTaskIds = orderedTasks.stream()
                .filter(Objects::nonNull)
                .map(com.product.domain.entity.OperationTask::getTaskId)
                .filter(StringUtils::isNotEmpty)
                .toList();
        Map<String, List<String>> postToPredecessors =
                taskSchedulingQueryService.loadPostToPredecessorsMap(schedulableTaskIds);
        java.util.Set<String> predecessorTaskIds = postToPredecessors.values().stream()
                .filter(CollectionUtils::isNotEmpty)
                .flatMap(List::stream)
                .filter(StringUtils::isNotEmpty)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        Map<String, LocalDateTime> predecessorEndTimes = new HashMap<>(
                taskSchedulingQueryService.loadPlannedEndByTaskIds(predecessorTaskIds));

        // 阶段2: 分批计算（核心耗时），进度 0-90%
        List<TaskSchedulingCalculator.ScheduleBatchResult> batchResults = new ArrayList<>();
        int processedTaskCount = 0;
        int lastProgressPushTaskCount = 0;
        long calculateStart = System.currentTimeMillis();

        for (int index = 0; index < orderedTasks.size(); index += scheduleBatchSize) {
            List<com.product.domain.entity.OperationTask> tasks =
                    orderedTasks.subList(index, Math.min(index + scheduleBatchSize, orderedTasks.size()));
            // 利用贪心算法级联选择 MACHINE -> MOLD -> PERSON
            TaskSchedulingCalculator.ScheduleBatchResult batchResult = taskSchedulingCalculator.calculateBatchAssignments(
                    tasks, schedulingContext, runtimeContext, assignmentStart, strategy,
                    postToPredecessors, predecessorEndTimes);
            batchResults.add(batchResult);
            processedTaskCount += tasks.size();

            lastProgressPushTaskCount = notifyScheduleProgressByTaskStep(progressConsumer,
                    totalTaskCount,
                    lastProgressPushTaskCount,
                    processedTaskCount,
                    batchResults.size());
        }

        if (CollectionUtils.isEmpty(batchResults)) {
            return ScheduleExecutionResult.success(totalTaskCount, 0);
        }

        // 阶段3: 统一入库（事务内）
        long persistStart = System.currentTimeMillis();
        Boolean persisted = transactionTemplate.execute(status ->
                taskAssignmentPersistenceService.persistAllBatchResults(batchResults, scheduleBatchSize));

        // 阶段4: 统计耗时
        long totalCost = System.currentTimeMillis() - totalStart;
        long calculateCost = persistStart - calculateStart;
        long persistCost = System.currentTimeMillis() - persistStart;

        // 阶段5: 进度推送 100%（成功）或标记失败
        if (Boolean.TRUE.equals(persisted)) {
            log.info("scheduleAll completed: tasks={}, batches={}, machines={}, batchSize={}, strategy={}, calculateCostMs={}, persistCostMs={}, totalCostMs={}",
                    totalTaskCount, batchResults.size(), machines.size(), scheduleBatchSize, strategy.getCode(), calculateCost, persistCost, totalCost);
            notifyScheduleProgress(progressConsumer, totalTaskCount, totalTaskCount, batchResults.size(), 100, SchedulePhase.SUCCESS);
            return ScheduleExecutionResult.success(totalTaskCount, batchResults.size());
        }

        log.warn("scheduleAll failed: tasks={}, batches={}, machines={}, batchSize={}, strategy={}, calculateCostMs={}, persistCostMs={}, totalCostMs={}",
                totalTaskCount, batchResults.size(), machines.size(), scheduleBatchSize, strategy.getCode(), calculateCost, persistCost, totalCost);
        return ScheduleExecutionResult.failure("排程结果落库失败", totalTaskCount, batchResults.size());
    }

    /**
     * 解析排程开始时间。
     */
    private LocalDateTime resolveAssignmentStart(TaskAssignmentDTO taskAssignmentDTO) {
        if (taskAssignmentDTO != null && taskAssignmentDTO.getAssignmentStart() != null) {
            return taskAssignmentDTO.getAssignmentStart();
        }
        return LocalDateTime.now();
    }

    /**
     * 向调用方推送排程进度。
     */
    private void notifyScheduleProgress(Consumer<ScheduleProgressDTO> progressConsumer,
                                        int totalTaskCount,
                                        int processedTaskCount,
                                        int batchCount,
                                        int progressPercent,
                                        SchedulePhase phase) {
        if (progressConsumer == null) {
            return;
        }

        ScheduleProgressDTO progress = new ScheduleProgressDTO();
        progress.setTotalTaskCount(totalTaskCount);
        progress.setProcessedTaskCount(processedTaskCount);
        progress.setBatchCount(batchCount);
        progress.setProgressPercent(progressPercent);
        progress.setPhase(phase.getCode());
        progressConsumer.accept(progress);
    }

    /**
     * 按任务数阈值推送计算阶段进度。
     */
    private int notifyScheduleProgressByTaskStep(Consumer<ScheduleProgressDTO> progressConsumer,
                                                  int totalTaskCount,
                                                  int lastProgressPushCount,
                                                  int processedTaskCount,
                                                  int batchCount) {
        if (progressConsumer == null || totalTaskCount <= 0) {
            return lastProgressPushCount;
        }

        int step = Math.max(1, progressPushTaskStep);
        int nextPushTaskCount = Math.max(step, lastProgressPushCount + step);
        int latestPushedTaskCount = lastProgressPushCount;

        while (nextPushTaskCount <= processedTaskCount) {
            int progressPercent = Math.min(90, (int) Math.round(nextPushTaskCount * 90.0 / totalTaskCount));
            notifyScheduleProgress(progressConsumer,
                    totalTaskCount,
                    nextPushTaskCount,
                    batchCount,
                    progressPercent,
                    SchedulePhase.CALCULATING);
            latestPushedTaskCount = nextPushTaskCount;
            nextPushTaskCount += step;
        }
        return latestPushedTaskCount;
    }
}
