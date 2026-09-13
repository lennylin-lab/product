package com.product.pps.service.impl;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.product.common.constant.RouteOperationConstants;
import com.product.common.constant.StatusConstants;
import com.product.common.core.result.AjaxResult;
import com.product.common.exception.ServiceException;
import com.product.common.utils.StringUtils;
import com.product.domain.entity.OperationTask;
import com.product.domain.entity.OrderLine;
import com.product.domain.entity.ProductMoldParam;
import com.product.domain.entity.ProductionBatch;
import com.product.domain.entity.TaskAssignment;
import com.product.domain.entity.TaskDependency;
import com.product.domain.entity.RouteOperation;
import com.product.domain.entity.TaskResourceRequirement;
import com.product.pps.dto.OperationTaskError;
import com.product.pps.mapper.OperationTaskMapper;
import com.product.pps.mapper.TaskAssignmentResourceMapper;
import com.product.pps.route.RouteDurationContext;
import com.product.pps.route.RouteRuleRegistry;
import com.product.pps.service.IOperationTaskService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;

/**
 * 工序任务Service业务层处理（MyBatis-Plus）
 *
 * @author product
 * @date 2025-12-31
 */
@Slf4j
@Service
public class OperationTaskServiceImpl extends ServiceImpl<OperationTaskMapper, OperationTask> implements IOperationTaskService {

    @Autowired
    @Qualifier("threadPoolTaskExecutor")
    private ThreadPoolTaskExecutor threadPoolTaskExecutor;
    @Autowired
    private TaskAssignmentResourceMapper taskAssignmentResourceMapper;
    @Autowired
    private ProductRouteQueryService productRouteQueryService;
    @Autowired
    private OperationResourceRequirementBuilder operationResourceRequirementBuilder;
    @Autowired
    private RouteRuleRegistry routeRuleRegistry;

    /**
     * 查询工序任务
     *
     * @param taskId 工序任务主键
     * @return 工序任务
     */
    @Override
    public OperationTask selectOperationTaskByTaskId(Long taskId) {
        return getById(taskId);
    }

    /**
     * 查询工序任务列表
     *
     * @param operationTask 查询条件
     * @return 工序任务集合
     */
    @Override
    public List<OperationTask> selectOperationTaskList(OperationTask operationTask) {
        return list(buildQueryWrapper(operationTask));
    }

    /**
     * 分页查询工序任务列表
     *
     * @param page      分页参数
     * @param operationTask 查询条件
     * @return 分页结果
     */
    @Override
    public Page<OperationTask> selectOperationTaskPage(Page<OperationTask> page, OperationTask operationTask) {
        return this.page(page, buildQueryWrapper(operationTask));
    }

    /**
     * 新增工序任务
     *
     * @param operationTask 工序任务
     * @return 是否成功
     */
    @Override
    public boolean insertOperationTask(OperationTask operationTask) {
        if (operationTask.getTaskId() == null) {
            operationTask.setTaskId(IdWorker.getId());
        }
        if (StringUtils.isEmpty(operationTask.getStatus())) {
            operationTask.setStatus(StatusConstants.READY_OPERATION_TASK);
        }
        boolean saved = save(operationTask);
        return saved;
    }

    /**
     * 批量新增工序任务
     *
     * @param operationTasks 工序任务列表
     * @return 成功条数
     */
    @Override
    public int batchInsertOperationTask(List<OperationTask> operationTasks) {
        if (CollectionUtils.isEmpty(operationTasks)) {
            return 0;
        }
        operationTasks.forEach(item -> {
            if (item.getTaskId() == null) {
                item.setTaskId(IdWorker.getId());
            }
        });
        boolean success = saveBatch(operationTasks);
        return success ? operationTasks.size() : 0;
    }

    /**
     * 修改工序任务
     *
     * @param operationTask 工序任务
     * @return 是否成功
     */
    @Override
    public boolean updateOperationTask(OperationTask operationTask) {
        boolean updated = updateById(operationTask);
        return updated;
    }

    /**
     * 批量删除工序任务
     *
     * @param taskIds 主键集合
     * @return 是否成功
     */
    @Override
    public boolean deleteOperationTaskByTaskIds(String[] taskIds) {
        if (taskIds == null || taskIds.length == 0) {
            return false;
        }
        return removeByIds(Arrays.asList(taskIds));
    }

    /**
     * 删除工序任务信息
     *
     * @param taskId 主键
     * @return 是否成功
     */
    @Override
    public boolean deleteOperationTaskByTaskId(Long taskId) {
        return removeById(taskId);
    }

    @Override
    public AjaxResult generateTask(List<Long> batchIds) {
        if (batchIds == null || batchIds.isEmpty()) {
            return AjaxResult.success("批次不能为空");
        }
        CompletableFuture<List<ProductionBatch>> productionBatchFuture = CompletableFuture.supplyAsync(() ->
                        Db.lambdaQuery(ProductionBatch.class)
                                .select(ProductionBatch::getBatchId, ProductionBatch::getStatus, ProductionBatch::getBatchQty)
                                .in(ProductionBatch::getBatchId, batchIds)
                                .list(),
                threadPoolTaskExecutor);
        CompletableFuture<List<Long>> existingTaskBatchFuture = CompletableFuture.supplyAsync(() ->
                        lambdaQuery()
                                .select(OperationTask::getBatchId)
                                .in(OperationTask::getBatchId, batchIds)
                                .list()
                                .stream()
                                .map(OperationTask::getBatchId)
                                .distinct()
                                .collect(Collectors.toList()),
                threadPoolTaskExecutor);
        List<ProductionBatch> productionBatches;
        List<Long> list;
        try {
            CompletableFuture.allOf(productionBatchFuture, existingTaskBatchFuture).join();
            productionBatches = productionBatchFuture.join();
            list = existingTaskBatchFuture.join();
        } catch (CompletionException e) {
            log.error("并行查询批次与任务信息失败，batchIds={}", batchIds, e);
            throw new ServiceException("查询批次任务信息失败");
        }
        // 错误集合
        List<OperationTaskError> errors = new ArrayList<>();

        List<OperationTask> operationTasks = new ArrayList<>();

        List<TaskDependency> taskDependencies = new ArrayList<>();
        List<TaskResourceRequirement> resourceRequirements = new ArrayList<>();
        Map<Long, ProductionBatch> batchMap = productionBatches.stream()
                .collect(Collectors.toMap(ProductionBatch::getBatchId, item -> item, (a, b) -> a));
        Map<Long, OrderLine> orderLineMap = loadOrderLineMap(productionBatches);
        Map<Long, ProductMoldParam> productMoldParamMap = loadProductMoldParamMap(orderLineMap.values());
        Map<Long, List<RouteOperation>> routeOperationsByProduct = productRouteQueryService
                .loadActiveRouteOperationsByProductIds(orderLineMap.values().stream()
                        .filter(Objects::nonNull)
                        .map(OrderLine::getProductId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet()));
        // 开始循环添加任务
        for (Long batchId : batchIds) {
            ProductionBatch item = batchMap.get(batchId);
            if (item == null) {
                OperationTaskError error = new OperationTaskError();
                error.setBatchId(batchId);
                error.setErrorMessage("批次不存在");
                errors.add(error);
                continue;
            }
            if (!item.getStatus().equals(StatusConstants.RELEASED_PRODUCTION_BATCH)) {
                OperationTaskError error = new OperationTaskError();
                error.setBatchId(item.getBatchId());
                error.setErrorMessage("批次未释放");
                errors.add(error);
                continue;
            }
            if (list.contains(item.getBatchId())) {
                OperationTaskError error = new OperationTaskError();
                error.setBatchId(item.getBatchId());
                error.setErrorMessage("批次已存在任务");
                errors.add(error);
                continue;
            }
            OrderLine orderLine = item.getOrderLineId() == null ? null : orderLineMap.get(item.getOrderLineId());
            Long productId = orderLine == null ? null : orderLine.getProductId();
            List<RouteOperation> routeOperations = productId == null
                    ? List.of()
                    : routeOperationsByProduct.getOrDefault(productId, List.of());
            if (CollectionUtils.isEmpty(routeOperations)) {
                OperationTaskError error = new OperationTaskError();
                error.setBatchId(item.getBatchId());
                error.setErrorMessage("产品未配置启用的工艺路线");
                errors.add(error);
                continue;
            }
            BatchTaskGenerationResult batchResult = buildBatchTasks(
                    item, routeOperations, orderLineMap, productMoldParamMap, productId);
            operationTasks.addAll(batchResult.tasks());
            taskDependencies.addAll(batchResult.dependencies());
            resourceRequirements.addAll(batchResult.resourceRequirements());
        }
        // 批量保存工序任务
        if (!operationTasks.isEmpty()) {
            saveBatch(operationTasks);
        }
        if (!taskDependencies.isEmpty()) {
            Db.saveBatch(taskDependencies);
        }
        if (!resourceRequirements.isEmpty()) {
            Db.saveBatch(resourceRequirements);
        }

        if (errors.isEmpty()) {
            AjaxResult result = AjaxResult.success("操作成功");
            result.put("errors", errors);
            return result;
        }
        if (!operationTasks.isEmpty()) {
            AjaxResult result = AjaxResult.success("操作完成，部分失败");
            result.put("errors", errors);
            return result;
        }
        Map<String, Object> data = new HashMap<>();
        data.put("errors", errors);
        return AjaxResult.error("操作失败", data);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AjaxResult retryGenerateTask(Long batchId) {
        List<OperationTask> tasks = lambdaQuery()
                .select(OperationTask::getStatus, OperationTask::getTaskId)
                .eq(OperationTask::getBatchId, batchId)
                .list();
        boolean hasNotAllowed = tasks.stream().anyMatch(item ->
                !StatusConstants.READY_OPERATION_TASK.equals(item.getStatus())
                        && !StatusConstants.SCHEDULED_OPERATION_TASK.equals(item.getStatus()));
        List<OperationTaskError> errors = new ArrayList<>();
        if (hasNotAllowed) {
            errors.add(new OperationTaskError(batchId, "仅允许READY或SCHEDULED状态的任务重新生成"));
            return AjaxResult.error("重新生成失败", errors);
        }
        List<Long> taskIds = tasks.stream()
                .map(OperationTask::getTaskId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (CollectionUtils.isNotEmpty(taskIds)) {
            taskAssignmentResourceMapper.deleteByTaskIds(taskIds);
            Db.lambdaUpdate(TaskAssignment.class)
                    .in(TaskAssignment::getTaskId, taskIds)
                    .remove();
        }
        lambdaUpdate().eq(OperationTask::getBatchId, batchId).remove();
        if (CollectionUtils.isNotEmpty(taskIds)) {
            Db.lambdaUpdate(TaskDependency.class)
                    .in(TaskDependency::getPreTaskId, taskIds)
                    .or()
                    .in(TaskDependency::getPostTaskId, taskIds)
                    .remove();
        }
        if (CollectionUtils.isNotEmpty(taskIds)) {
            Db.lambdaUpdate(TaskResourceRequirement.class)
                    .in(TaskResourceRequirement::getTaskId, taskIds)
                    .remove();
        }
        ProductionBatch productionBatch = Db.lambdaQuery(ProductionBatch.class)
                .select(ProductionBatch::getBatchId, ProductionBatch::getStatus, ProductionBatch::getBatchQty)
                .eq(ProductionBatch::getBatchId, batchId)
                .last("limit 1").one();
        if (productionBatch == null) {
            errors.add(new OperationTaskError(batchId, "批次不存在"));
            return AjaxResult.error("重新生成失败", errors);
        }
        if (!productionBatch.getStatus().equals(StatusConstants.RELEASED_PRODUCTION_BATCH)) {
            errors.add(new OperationTaskError(batchId, "该批次当前状态不允许修改"));
            return AjaxResult.error("重新生成失败", errors);
        }
        Map<Long, OrderLine> orderLineMap = loadOrderLineMap(List.of(productionBatch));
        Map<Long, ProductMoldParam> productMoldParamMap = loadProductMoldParamMap(orderLineMap.values());
        OrderLine orderLine = productionBatch.getOrderLineId() == null
                ? null
                : orderLineMap.get(productionBatch.getOrderLineId());
        Long productId = orderLine == null ? null : orderLine.getProductId();
        Map<Long, List<RouteOperation>> routeOperationsByProduct = productRouteQueryService
                .loadActiveRouteOperationsByProductIds(productId == null ? List.of() : List.of(productId));
        List<RouteOperation> routeOperations = productId == null
                ? List.of()
                : routeOperationsByProduct.getOrDefault(productId, List.of());
        if (CollectionUtils.isEmpty(routeOperations)) {
            errors.add(new OperationTaskError(batchId, "产品未配置启用的工艺路线"));
            return AjaxResult.error("重新生成失败", errors);
        }
        BatchTaskGenerationResult batchResult = buildBatchTasks(
                productionBatch, routeOperations, orderLineMap, productMoldParamMap, productId);
        saveBatch(batchResult.tasks());
        if (!batchResult.dependencies().isEmpty()) {
            Db.saveBatch(batchResult.dependencies());
        }
        if (!batchResult.resourceRequirements().isEmpty()) {
            Db.saveBatch(batchResult.resourceRequirements());
        }
        return AjaxResult.success();
    }

    @Override
    public boolean cancel(Long taskId) {
        return lambdaUpdate().set(OperationTask::getStatus, StatusConstants.CANCELLED_OPERATION_TASK)
                .eq(OperationTask::getTaskId, taskId)
                .update();
    }

    @Override
    public boolean restore(Long taskId) {
        return lambdaUpdate().set(OperationTask::getStatus, StatusConstants.READY_OPERATION_TASK)
                .eq(OperationTask::getTaskId, taskId)
                .update();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean revokeSchedule(Long taskId) {
        if (taskId != null) {
            taskAssignmentResourceMapper.deleteByTaskIds(List.of(taskId));
        }
        // 删除派工记录
        Db.lambdaUpdate(TaskAssignment.class)
                .eq(TaskAssignment::getTaskId, taskId)
                .remove();
        return lambdaUpdate().set(OperationTask::getStatus, StatusConstants.READY_OPERATION_TASK)
                .eq(OperationTask::getTaskId, taskId)
                .update();
    }

    @Override
    public Page<OperationTask> selectReadyAndScheduledPage(Page<OperationTask> page, OperationTask operationTask) {
        operationTask.setStatusList(Arrays.asList("READY", "SCHEDULED"));
        return this.page(page, buildQueryWrapper(operationTask));
    }

    /**
     * 构建查询条件
     */
    private LambdaQueryWrapper<OperationTask> buildQueryWrapper(OperationTask operationTask) {
        LambdaQueryWrapper<OperationTask> wrapper = new LambdaQueryWrapper<>();
        if (operationTask == null) {
            return wrapper;
        }
        wrapper.eq(operationTask.getBatchId() != null, OperationTask::getBatchId, operationTask.getBatchId());
        wrapper.eq(operationTask.getOpCode() != null, OperationTask::getOpCode, operationTask.getOpCode());
        wrapper.eq(operationTask.getSequence() != null, OperationTask::getSequence, operationTask.getSequence());
        wrapper.eq(operationTask.getStdDurationMin() != null, OperationTask::getStdDurationMin, operationTask.getStdDurationMin());
        wrapper.eq(operationTask.getEarliestStart() != null, OperationTask::getEarliestStart, operationTask.getEarliestStart());
        wrapper.eq(operationTask.getStatus() != null, OperationTask::getStatus, operationTask.getStatus());
        wrapper.in(operationTask.getStatusList() != null, OperationTask::getStatus, operationTask.getStatusList());
        return wrapper;
    }


    private BatchTaskGenerationResult buildBatchTasks(ProductionBatch batch,
                                                      List<RouteOperation> routeOperations,
                                                      Map<Long, OrderLine> orderLineMap,
                                                      Map<Long, ProductMoldParam> productMoldParamMap,
                                                      Long productId) {
        LocalDateTime baseTime = LocalDateTime.now();
        long cumulativeMinutes = 0;
        long batchQty = batch.getBatchQty() == null ? 1L : batch.getBatchQty();
        List<OperationTask> batchTasks = new ArrayList<>();
        List<TaskDependency> dependencies = new ArrayList<>();
        List<TaskResourceRequirement> resourceRequirements = new ArrayList<>();
        OperationTask previousTask = null;
        for (RouteOperation routeOperation : routeOperations) {
            if (routeOperation == null || StringUtils.isEmpty(routeOperation.getOpCode())) {
                continue;
            }
            OperationTask operationTask = new OperationTask();
            operationTask.setTaskId(IdWorker.getId());
            operationTask.setBatchId(batch.getBatchId());
            operationTask.setStatus(StatusConstants.READY_OPERATION_TASK);
            operationTask.setOpCode(routeOperation.getOpCode());
            operationTask.setProductId(productId);
            operationTask.setQueuePolicy(routeOperation.getQueuePolicy());
            operationTask.setEligibleResourceRule(routeOperation.getEligibleResourceRule());
            operationTask.setStdDurationMin(resolveStdDurationMin(
                    routeOperation.getStdTimeModel(),
                    routeOperation.getOpCode(),
                    batchQty,
                    batch,
                    orderLineMap,
                    productMoldParamMap));
            operationTask.setEarliestStart(baseTime.plus(cumulativeMinutes, ChronoUnit.MINUTES));
            operationTask.setSequence(routeOperation.getSequence() == null
                    ? Long.valueOf(batchTasks.size() + 1L)
                    : routeOperation.getSequence().longValue());
            cumulativeMinutes += operationTask.getStdDurationMin();
            batchTasks.add(operationTask);
            resourceRequirements.addAll(operationResourceRequirementBuilder.buildRequirements(operationTask, routeOperation));
            if (previousTask != null) {
                TaskDependency dependency = new TaskDependency();
                dependency.setPreTaskId(previousTask.getTaskId());
                dependency.setPostTaskId(operationTask.getTaskId());
                dependencies.add(dependency);
            }
            previousTask = operationTask;
        }
        return new BatchTaskGenerationResult(batchTasks, dependencies, resourceRequirements);
    }

    private record BatchTaskGenerationResult(List<OperationTask> tasks,
                                             List<TaskDependency> dependencies,
                                             List<TaskResourceRequirement> resourceRequirements) {
    }

    private Map<Long, OrderLine> loadOrderLineMap(Collection<ProductionBatch> batches) {
        if (CollectionUtils.isEmpty(batches)) {
            return Map.of();
        }
        Set<Long> orderLineIds = batches.stream()
                .filter(Objects::nonNull)
                .map(ProductionBatch::getOrderLineId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (orderLineIds.isEmpty()) {
            return Map.of();
        }
        return Db.lambdaQuery(OrderLine.class)
                .in(OrderLine::getOrderLineId, orderLineIds)
                .list()
                .stream()
                .filter(Objects::nonNull)
                .filter(item -> item.getOrderLineId() != null)
                .collect(Collectors.toMap(OrderLine::getOrderLineId, item -> item, (left, right) -> left));
    }

    private Map<Long, ProductMoldParam> loadProductMoldParamMap(Collection<OrderLine> orderLines) {
        if (CollectionUtils.isEmpty(orderLines)) {
            return Map.of();
        }
        Set<Long> productIds = orderLines.stream()
                .filter(Objects::nonNull)
                .map(OrderLine::getProductId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (productIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, ProductMoldParam> paramMap = new HashMap<>();
        Db.lambdaQuery(ProductMoldParam.class)
                .in(ProductMoldParam::getProductId, productIds)
                .list()
                .stream()
                .filter(Objects::nonNull)
                .filter(item -> item.getProductId() != null)
                .forEach(item -> paramMap.putIfAbsent(item.getProductId(), item));
        return paramMap;
    }

    private long resolveStdDurationMin(String stdTimeModel,
                                       String opCode,
                                       long batchQty,
                                       ProductionBatch batch,
                                       Map<Long, OrderLine> orderLineMap,
                                       Map<Long, ProductMoldParam> productMoldParamMap) {
        OrderLine orderLine = batch == null || batch.getOrderLineId() == null
                ? null
                : orderLineMap.get(batch.getOrderLineId());
        Long productId = orderLine == null ? null : orderLine.getProductId();
        ProductMoldParam param = productId == null ? null : productMoldParamMap.get(productId);
        RouteDurationContext context = new RouteDurationContext(
                batchQty, productId, param, opCode, stdTimeModel);
        return routeRuleRegistry.calculateDurationMin(context);
    }
}
