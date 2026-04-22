package com.product.pps.service.impl;

import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.product.common.constant.ResourceConstants;
import com.product.common.constant.StatusConstants;
import com.product.common.utils.StringUtils;
import com.product.domain.entity.CustomerOrder;
import com.product.domain.entity.Calendar;
import com.product.domain.entity.OperationTask;
import com.product.domain.entity.OrderLine;
import com.product.domain.entity.ProductionBatch;
import com.product.domain.entity.Resource;
import com.product.domain.entity.TaskAssignment;
import com.product.pps.dto.TaskSchedulingPriorityDTO;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 排程查询服务。
 *
 * <p>
 * <strong>职责：</strong>负责排程相关的数据查询与预处理，包括：
 * </p>
 * <ul>
 * <li>可用机台资源的加载</li>
 * <li>班次日历的批量查询</li>
 * <li>待排任务的标准化处理（去重、过滤、排序）</li>
 * <li>任务优先级的数据组装（通过多表关联获取交期和优先级）</li>
 * </ul>
 *
 * <p>
 * <strong>设计特点：</strong>
 * </p>
 * <ul>
 * <li>所有查询方法都具备防御性编程（空值处理）</li>
 * <li>批量查询使用 IN 语句避免 N+1 问题</li>
 * <li>标准化逻辑分离，便于单元测试</li>
 * </ul>
 */
@Slf4j
@Component
public class TaskSchedulingQueryService {

    /**
     * 加载可用机台列表。
     *
     * <p>
     * 查询条件：资源类型为机台且状态为可用。
     * </p>
     *
     * @return 可用机台列表，不会返回 null
     */
    public List<Resource> loadAvailableMachines() {
        // 使用 MyBatis-Plus 的 Db 工具进行 Lambda 查询
        List<Resource> resources = Db.lambdaQuery(Resource.class)
                .eq(Resource::getResourceType, ResourceConstants.RESOURCE_TYPE_MACHINE) // 筛选机台类型
                .eq(Resource::getStatus, StatusConstants.AVAILABLE_RESOURCE_STATUS) // 筛选可用状态
                .list();
        // 防御性处理：如果结果为空，直接返回
        if (CollectionUtils.isEmpty(resources)) {
            return resources;
        }
        // 过滤掉可能存在的 null 元素（虽然数据库查询通常不会返回 null）
        return resources.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * 加载机台对应的班次日历映射。
     *
     * <p>
     * 根据机台列表提取所有 calendarId，批量查询对应的 Calendar 实体，
     * 并以 calendarId 为键建立 Map，方便后续根据机台快速查找日历。
     * </p>
     *
     * @param machines 机台列表
     * @return calendarId → Calendar 的映射，不会返回 null
     */
    public Map<Long, Calendar> loadCalendarMap(List<Resource> machines) {
        // 提取所有机台的 calendarId（去重）
        Set<Long> calendarIds = new HashSet<>();
        for (Resource machine : machines) {
            if (machine != null && machine.getCalendarId() != null) {
                calendarIds.add(machine.getCalendarId());
            }
        }
        // 如果没有关联的日历，返回空 Map
        if (calendarIds.isEmpty()) {
            return new HashMap<>();
        }
        // 批量查询日历并转换为 Map：calendarId → Calendar
        // (a, b) -> a 表示冲突时保留先出现的值
        return Db.lambdaQuery(Calendar.class)
                .in(Calendar::getCalendarId, calendarIds)
                .list()
                .stream()
                .collect(Collectors.toMap(Calendar::getCalendarId, item -> item, (a, b) -> a));
    }

    /**
     * 统计 READY 状态的任务数量。
     *
     * @return READY 状态任务数，数据库异常时返回 0
     */
    public int countReadyTasks() {
        // count() 返回 Long 类型，需要转换为 int 并处理 null 情况
        Long count = Db.lambdaQuery(OperationTask.class)
                .eq(OperationTask::getStatus, StatusConstants.READY_OPERATION_TASK)
                .count();
        return count == null ? 0 : count.intValue();
    }

    /** 加载所有 READY 状态的任务列表，经标准化（去重、排除已派工、稳定排序）后返回 */
    public List<OperationTask> loadReadyTaskList() {
        List<OperationTask> readyTasks = Db.lambdaQuery(OperationTask.class)
                .eq(OperationTask::getStatus, StatusConstants.READY_OPERATION_TASK)
                .list();
        return normalizeReadyTasksForScheduling(readyTasks);
    }

    /**
     * 根据任务 ID 加载单个任务。
     *
     * @param taskId 任务唯一标识
     * @return 任务实体，找不到或 taskId 为空时返回 null
     */
    public OperationTask loadTaskByTaskId(String taskId) {
        // 参数校验：taskId 为空时直接返回 null，避免无效查询
        if (StringUtils.isEmpty(taskId)) {
            return null;
        }
        return Db.lambdaQuery(OperationTask.class)
                .eq(OperationTask::getTaskId, taskId)
                .one(); // one() 返回单条记录，找不到返回 null
    }

    /**
     * 将 READY 任务标准化为适合排程的稳定输入集合。
     *
     * 处理内容：
     * 1. 过滤 null、空 taskId、非 READY 任务
     * 2. 按 taskId 去重，保留首个任务
     * 3. 过滤已经存在派工记录的任务，避免重复派工
     * 4. 统一按 earliestStart + batchId + sequence + taskId 排序，保证输入稳定
     */
    public List<OperationTask> normalizeReadyTasksForScheduling(List<OperationTask> tasks) {
        if (CollectionUtils.isEmpty(tasks)) {
            return new ArrayList<>();
        }
        Map<String, OperationTask> distinctTaskMap = tasks.stream()
                .filter(Objects::nonNull)
                .filter(item -> StringUtils.isNotEmpty(item.getTaskId()))
                .filter(item -> StatusConstants.READY_OPERATION_TASK.equals(item.getStatus()))
                .collect(Collectors.toMap(OperationTask::getTaskId, item -> item, (left, right) -> left,
                        LinkedHashMap::new));
        if (distinctTaskMap.isEmpty()) {
            return new ArrayList<>();
        }
        Set<String> assignedTaskIds = loadExistingAssignmentTaskIds(new ArrayList<>(distinctTaskMap.keySet()));
        return normalizeReadyTasksForScheduling(new ArrayList<>(distinctTaskMap.values()), assignedTaskIds);
    }

    /**
     * 对外暴露纯内存标准化逻辑，便于单元测试。
     */
    List<OperationTask> normalizeReadyTasksForScheduling(List<OperationTask> tasks, Set<String> assignedTaskIds) {
        if (CollectionUtils.isEmpty(tasks)) {
            return new ArrayList<>();
        }
        Set<String> existingTaskIds = assignedTaskIds == null ? Collections.emptySet() : assignedTaskIds;
        // 過濾
        Map<String, OperationTask> distinctTaskMap = tasks.stream()
                .filter(Objects::nonNull)
                .filter(item -> StringUtils.isNotEmpty(item.getTaskId()))
                .filter(item -> StatusConstants.READY_OPERATION_TASK.equals(item.getStatus()))
                .filter(item -> !existingTaskIds.contains(item.getTaskId()))
                .collect(Collectors.toMap(OperationTask::getTaskId, item -> item, (left, right) -> left,
                        LinkedHashMap::new));
        if (distinctTaskMap.isEmpty()) {
            return new ArrayList<>();
        }
        List<OperationTask> orderedTasks = new ArrayList<>(distinctTaskMap.values());
        orderedTasks.sort(Comparator
                .comparing(OperationTask::getEarliestStart, Comparator.nullsLast(LocalDateTime::compareTo))
                .thenComparing(OperationTask::getBatchId, Comparator.nullsLast(String::compareTo))
                .thenComparing(OperationTask::getSequence, Comparator.nullsLast(Long::compareTo))
                .thenComparing(OperationTask::getTaskId, Comparator.nullsLast(String::compareTo)));
        return orderedTasks;
    }

    /**
     * 查询已存在派工记录的任务 ID，用于排程前去重，避免重复派工。
     *
     * <p>
     * 通过 IN 查询批量检查任务是否已有派工记录，返回有派工记录的任务 ID 集合。
     * </p>
     *
     * @param taskIds 待检查的任务 ID 列表
     * @return 已有派工记录的任务 ID 集合，不会返回 null
     */
    private Set<String> loadExistingAssignmentTaskIds(List<String> taskIds) {
        // 防御性处理：空列表直接返回空集合
        if (CollectionUtils.isEmpty(taskIds)) {
            return Collections.emptySet();
        }
        // 使用 Stream API 处理查询结果：
        // 1. 只查询 taskId 字段减少数据传输
        // 2. map() 提取 taskId 字段（实体 → 字符串）
        // 3. filter() 过滤空值
        // 4. collect() 收集到 HashSet（去重）
        return Db.lambdaQuery(TaskAssignment.class)
                .select(TaskAssignment::getTaskId) // 只查询 taskId 字段
                .in(TaskAssignment::getTaskId, taskIds) // IN 查询批量筛选
                .list() // 返回 List<TaskAssignment>
                .stream() // 转为 Stream<TaskAssignment>
                .map(TaskAssignment::getTaskId) // 提取 taskId：Stream<String>
                .filter(StringUtils::isNotEmpty) // 过滤空字符串
                .collect(Collectors.toCollection(HashSet::new)); // 收集为 HashSet（自动去重）
    }

    /**
     * 加载任务的优先级上下文（交期 + 优先级）。
     *
     * <p>
     * <strong>数据链路：</strong>OperationTask → ProductionBatch → OrderLine →
     * CustomerOrder
     * </p>
     * <p>
     * 从订单中提取 dueDate（交期）和 priority（优先级），用于 DUE_DATE_PRIORITY 策略排序。
     * </p>
     *
     * <p>
     * 查询策略采用批量 IN 查询，避免 N+1 问题：
     * 1. 批量查询 ProductionBatch（通过 batchId）
     * 2. 批量查询 OrderLine（通过 orderLineId）
     * 3. 批量查询 CustomerOrder（通过 orderId）
     * </p>
     *
     * @param tasks 待排程任务列表
     * @return 任务ID → 优先级上下文（找不到关联订单时交期和优先级为 null）
     */
    public Map<String, TaskSchedulingPriorityDTO> loadTaskPriorityMap(List<OperationTask> tasks) {
        if (CollectionUtils.isEmpty(tasks)) {
            return new HashMap<>();
        }

        // 步骤1：提取所有 batchId（任务 → 批次）
        Set<String> batchIds = tasks.stream()
                .map(OperationTask::getBatchId) // 提取 batchId
                .filter(StringUtils::isNotEmpty) // 过滤空值
                .collect(Collectors.toSet()); // 去重收集
        if (batchIds.isEmpty()) {
            return new HashMap<>();
        }

        // 步骤2：批量查询批次，获取批次与订单行的映射
        List<ProductionBatch> batches = Db.lambdaQuery(ProductionBatch.class)
                .select(ProductionBatch::getBatchId, ProductionBatch::getOrderLineId)
                .in(ProductionBatch::getBatchId, batchIds)
                .list();
        if (CollectionUtils.isEmpty(batches)) {
            return new HashMap<>();
        }

        // 构建映射：batchId → orderLineId
        Map<String, Long> batchToOrderLine = batches.stream()
                .filter(Objects::nonNull)
                .filter(item -> StringUtils.isNotEmpty(item.getBatchId()) && item.getOrderLineId() != null)
                .collect(Collectors.toMap(ProductionBatch::getBatchId, ProductionBatch::getOrderLineId, (a, b) -> a));

        // 步骤3：批量查询订单行，获取订单行与订单的映射
        Set<Long> orderLineIds = new HashSet<>(batchToOrderLine.values());
        List<OrderLine> orderLines = Db.lambdaQuery(OrderLine.class)
                .select(OrderLine::getOrderLineId, OrderLine::getOrderId)
                .in(OrderLine::getOrderLineId, orderLineIds)
                .list();
        // 构建映射：orderLineId → orderId
        Map<Long, String> orderLineToOrderId = orderLines.stream()
                .filter(Objects::nonNull)
                .filter(item -> item.getOrderLineId() != null && StringUtils.isNotEmpty(item.getOrderId()))
                .collect(Collectors.toMap(OrderLine::getOrderLineId, OrderLine::getOrderId, (a, b) -> a));

        // 步骤4：批量查询订单，获取订单详情（交期、优先级）
        Set<String> orderIds = new HashSet<>(orderLineToOrderId.values());
        List<CustomerOrder> orders = Db.lambdaQuery(CustomerOrder.class)
                .select(CustomerOrder::getOrderId, CustomerOrder::getDueDate, CustomerOrder::getPriority)
                .in(CustomerOrder::getOrderId, orderIds)
                .list();
        // 构建映射：orderId → CustomerOrder
        Map<String, CustomerOrder> orderMap = orders.stream()
                .filter(Objects::nonNull)
                .filter(item -> StringUtils.isNotEmpty(item.getOrderId()))
                .collect(Collectors.toMap(CustomerOrder::getOrderId, item -> item, (a, b) -> a));

        // 步骤5：组装最终结果，遍历任务建立完整的优先级上下文
        Map<String, TaskSchedulingPriorityDTO> priorityMap = new HashMap<>();
        for (OperationTask task : tasks) {
            if (task == null || StringUtils.isEmpty(task.getTaskId())) {
                continue;
            }
            TaskSchedulingPriorityDTO context = new TaskSchedulingPriorityDTO();
            context.setTaskId(task.getTaskId());
            context.setBatchId(task.getBatchId());

            // 链式查找：task.batchId → orderLineId → orderId → order
            Long orderLineId = task.getBatchId() == null ? null : batchToOrderLine.get(task.getBatchId());
            if (orderLineId != null) {
                String orderId = orderLineToOrderId.get(orderLineId);
                if (StringUtils.isNotEmpty(orderId)) {
                    CustomerOrder order = orderMap.get(orderId);
                    if (order != null) {
                        context.setDueDate(order.getDueDate()); // 设置交期
                        context.setPriority(order.getPriority()); // 设置优先级
                    }
                }
            }
            priorityMap.put(task.getTaskId(), context);
        }
        return priorityMap;
    }
}
