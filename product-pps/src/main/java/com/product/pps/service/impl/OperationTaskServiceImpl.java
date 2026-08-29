package com.product.pps.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.product.common.annotation.BizIdPrefix;
import com.product.common.constant.OperationTaskConstants;
import com.product.common.constant.ResourceConstants;
import com.product.common.constant.StatusConstants;
import com.product.common.core.result.AjaxResult;
import com.product.common.exception.ServiceException;
import com.product.common.utils.StringUtils;
import com.product.common.utils.uuid.IdUtils;
import com.product.domain.entity.OperationTask;
import com.product.domain.entity.OrderLine;
import com.product.domain.entity.ProductMoldParam;
import com.product.domain.entity.ProductionBatch;
import com.product.domain.entity.TaskAssignment;
import com.product.domain.entity.TaskDependency;
import com.product.domain.entity.TaskResourceRequirement;
import com.product.pps.dto.OperationTaskError;
import com.product.pps.mapper.OperationTaskMapper;
import com.product.pps.mapper.TaskAssignmentResourceMapper;
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
    private InjectDurationCalculator injectDurationCalculator;
    @Autowired
    private TaskAssignmentResourceMapper taskAssignmentResourceMapper;

    /**
     * 查询工序任务
     *
     * @param taskId 工序任务主键
     * @return 工序任务
     */
    @Override
    public OperationTask selectOperationTaskByTaskId(String taskId) {
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
        if (StringUtils.isEmpty(operationTask.getTaskId())) {
            operationTask.setTaskId(buildBizId(operationTask));
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
            if (StringUtils.isEmpty(item.getTaskId())) {
                item.setTaskId(buildBizId(item));
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
    public boolean deleteOperationTaskByTaskId(String taskId) {
        return removeById(taskId);
    }

    @Override
    public AjaxResult generateTask(List<String> batchIds) {
        if (batchIds == null || batchIds.isEmpty()) {
            return AjaxResult.success("批次不能为空");
        }
        CompletableFuture<List<ProductionBatch>> productionBatchFuture = CompletableFuture.supplyAsync(() ->
                        Db.lambdaQuery(ProductionBatch.class)
                                .select(ProductionBatch::getBatchId, ProductionBatch::getStatus, ProductionBatch::getBatchQty)
                                .in(ProductionBatch::getBatchId, batchIds)
                                .list(),
                threadPoolTaskExecutor);
        CompletableFuture<List<String>> existingTaskBatchFuture = CompletableFuture.supplyAsync(() ->
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
        List<String> list;
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
        Map<String, ProductionBatch> batchMap = productionBatches.stream()
                .collect(Collectors.toMap(ProductionBatch::getBatchId, item -> item, (a, b) -> a));
        Map<Long, OrderLine> orderLineMap = loadOrderLineMap(productionBatches);
        Map<Long, ProductMoldParam> productMoldParamMap = loadProductMoldParamMap(orderLineMap.values());
        // 开始循环添加任务
        for (String batchId : batchIds) {
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
            LocalDateTime baseTime = LocalDateTime.now();
            long cumulativeMinutes = 0; // 累计时长（分钟）
            long batchQty = item.getBatchQty() == null ? 1L : item.getBatchQty();
            List<OperationTask> batchTasks = new ArrayList<>();
            for (int i = 0; i < 3;) {
                OperationTask operationTask = new OperationTask();
                operationTask.setTaskId(buildBizId(operationTask));
                operationTask.setBatchId(item.getBatchId());
                operationTask.setStatus(StatusConstants.READY_OPERATION_TASK);
                operationTask.setOpCode(OperationTaskConstants.OP_CODE.get(i));
                operationTask.setStdDurationMin(resolveStdDurationMin(
                        OperationTaskConstants.OP_CODE.get(i),
                        batchQty,
                        item,
                        orderLineMap,
                        productMoldParamMap));
                // 最早开始时间 = 当前时间 + 前面所有工序的累计时长
                operationTask.setEarliestStart(baseTime.plus(cumulativeMinutes, ChronoUnit.MINUTES));
                operationTask.setSequence(Long.valueOf(++i));
                // 累加当前工序的时长，用于下一个工序的计算
                cumulativeMinutes += operationTask.getStdDurationMin();
                // 添加到列表
                operationTasks.add(operationTask);
                batchTasks.add(operationTask);
                // 构建资源需求
                resourceRequirements.addAll(buildResourceRequirements(operationTask));
            }
            if (batchTasks.size() >= 3) {
                TaskDependency setupToInject = new TaskDependency();
                setupToInject.setPreTaskId(batchTasks.get(0).getTaskId());
                setupToInject.setPostTaskId(batchTasks.get(1).getTaskId());
                taskDependencies.add(setupToInject);
                TaskDependency injectToPost = new TaskDependency();
                injectToPost.setPreTaskId(batchTasks.get(1).getTaskId());
                injectToPost.setPostTaskId(batchTasks.get(2).getTaskId());
                taskDependencies.add(injectToPost);
            }
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
    public AjaxResult retryGenerateTask(String batchId) {
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
        List<String> taskIds = tasks.stream()
                .map(OperationTask::getTaskId)
                .filter(StringUtils::isNotEmpty)
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
        LocalDateTime baseTime = LocalDateTime.now();
        long cumulativeMinutes = 0; // 累计时长（分钟）
        long batchQty = productionBatch.getBatchQty() == null ? 1L : productionBatch.getBatchQty();
        Map<Long, OrderLine> orderLineMap = loadOrderLineMap(List.of(productionBatch));
        Map<Long, ProductMoldParam> productMoldParamMap = loadProductMoldParamMap(orderLineMap.values());
        List<OperationTask> operationTasks = new ArrayList<>();
        List<TaskDependency> taskDependencies = new ArrayList<>();
        List<TaskResourceRequirement> resourceRequirements = new ArrayList<>();
        for (int i = 0; i < 3;) {
            OperationTask operationTask = new OperationTask();
            operationTask.setTaskId(buildBizId(operationTask));
            operationTask.setBatchId(batchId);
            operationTask.setStatus(StatusConstants.READY_OPERATION_TASK);
            operationTask.setOpCode(OperationTaskConstants.OP_CODE.get(i));
            operationTask.setStdDurationMin(resolveStdDurationMin(
                    OperationTaskConstants.OP_CODE.get(i),
                    batchQty,
                    productionBatch,
                    orderLineMap,
                    productMoldParamMap));
            // 最早开始时间 = 当前时间 + 前面所有工序的累计时长
            operationTask.setEarliestStart(baseTime.plus(cumulativeMinutes, ChronoUnit.MINUTES));
            operationTask.setSequence(Long.valueOf(++i));
            // 累加当前工序的时长，用于下一个工序的计算
            cumulativeMinutes += operationTask.getStdDurationMin();
            // 添加到列表
            operationTasks.add(operationTask);
            // 构建资源需求
            resourceRequirements.addAll(buildResourceRequirements(operationTask));
        }
        saveBatch(operationTasks);
        if (operationTasks.size() >= 3) {
            TaskDependency setupToInject = new TaskDependency();
            setupToInject.setPreTaskId(operationTasks.get(0).getTaskId());
            setupToInject.setPostTaskId(operationTasks.get(1).getTaskId());
            taskDependencies.add(setupToInject);
            TaskDependency injectToPost = new TaskDependency();
            injectToPost.setPreTaskId(operationTasks.get(1).getTaskId());
            injectToPost.setPostTaskId(operationTasks.get(2).getTaskId());
            taskDependencies.add(injectToPost);
            Db.saveBatch(taskDependencies);
        }
        if (!resourceRequirements.isEmpty()) {
            Db.saveBatch(resourceRequirements);
        }
        return AjaxResult.success();
    }

    @Override
    public boolean cancel(String taskId) {
        return lambdaUpdate().set(OperationTask::getStatus, StatusConstants.CANCELLED_OPERATION_TASK)
                .eq(OperationTask::getTaskId, taskId)
                .update();
    }

    @Override
    public boolean restore(String taskId) {
        return lambdaUpdate().set(OperationTask::getStatus, StatusConstants.READY_OPERATION_TASK)
                .eq(OperationTask::getTaskId, taskId)
                .update();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean revokeSchedule(String taskId) {
        if (StringUtils.isNotEmpty(taskId)) {
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


    /**
     * 根据工序类型构建资源需求列表
     * SETUP: PERSON(调机员) + MACHINE
     * INJECT: PERSON(操作员) + MACHINE + MOLD
     * POST_QC_PUTAWAY: PERSON(质检员) + MACHINE
     */
    private List<TaskResourceRequirement> buildResourceRequirements(OperationTask task) {
        List<TaskResourceRequirement> requirements = new ArrayList<>();
        String opCode = task.getOpCode();
        switch (opCode) {
            case "SETUP" -> {
                requirements.add(createRequirement(task.getTaskId(), ResourceConstants.RESOURCE_TYPE_PERSON, "调机员", opCode));
                requirements.add(createRequirement(task.getTaskId(), ResourceConstants.RESOURCE_TYPE_MACHINE, null, opCode));
            }
            case "INJECT" -> {
                requirements.add(createRequirement(task.getTaskId(), ResourceConstants.RESOURCE_TYPE_PERSON, "操作员", opCode));
                requirements.add(createRequirement(task.getTaskId(), ResourceConstants.RESOURCE_TYPE_MACHINE, null, opCode));
                requirements.add(createRequirement(task.getTaskId(), ResourceConstants.RESOURCE_TYPE_MOLD, null, opCode));
            }
            case "POST_QC_PUTAWAY" -> {
                requirements.add(createRequirement(task.getTaskId(), ResourceConstants.RESOURCE_TYPE_PERSON, "质检员", opCode));
                requirements.add(createRequirement(task.getTaskId(), ResourceConstants.RESOURCE_TYPE_MACHINE, null, opCode));
            }
            default -> log.warn("未知的工序类型: {}, taskId={}", opCode, task.getTaskId());
        }
        return requirements;
    }

    private TaskResourceRequirement createRequirement(String taskId, String resourceType, String resourceRole, String capabilityCode) {
        TaskResourceRequirement requirement = new TaskResourceRequirement();
        requirement.setTaskId(taskId);
        requirement.setResourceType(resourceType);
        requirement.setResourceRole(resourceRole);
        requirement.setCapabilityCode(capabilityCode);
        requirement.setRequiredCount(1);
        requirement.setIsMandatory(1);
        return requirement;
    }

    private String buildBizId(Object entity) {
        BizIdPrefix annotation = entity.getClass().getAnnotation(BizIdPrefix.class);
        String prefix = annotation != null ? annotation.value() : null;
        String suffix = IdUtils.simpleUUID();
        return StringUtils.isNotEmpty(prefix) ? prefix + suffix : suffix;
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

    private long resolveStdDurationMin(String opCode,
                                       long batchQty,
                                       ProductionBatch batch,
                                       Map<Long, OrderLine> orderLineMap,
                                       Map<Long, ProductMoldParam> productMoldParamMap) {
        if ("INJECT".equals(opCode)) {
            return calculateInjectDurationMin(batch, orderLineMap, productMoldParamMap);
        }
        int opIndex = OperationTaskConstants.OP_CODE.indexOf(opCode);
        long baseDuration = opIndex >= 0
                ? OperationTaskConstants.STD_DURATION_MIM.get(opIndex)
                : OperationTaskConstants.STD_DURATION_MIM.get(1);
        return baseDuration * batchQty;
    }

    private long calculateInjectDurationMin(ProductionBatch batch,
                                            Map<Long, OrderLine> orderLineMap,
                                            Map<Long, ProductMoldParam> productMoldParamMap) {
        OrderLine orderLine = batch == null || batch.getOrderLineId() == null
                ? null
                : orderLineMap.get(batch.getOrderLineId());
        Long productId = orderLine == null ? null : orderLine.getProductId();
        ProductMoldParam param = productId == null ? null : productMoldParamMap.get(productId);
        long batchQty = batch.getBatchQty() == null ? 1L : batch.getBatchQty();
        return injectDurationCalculator.calculateDurationMin(batchQty, param, productId);
    }
}
