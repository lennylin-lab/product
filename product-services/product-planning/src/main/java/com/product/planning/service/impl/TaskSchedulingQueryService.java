package com.product.planning.service.impl;

import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.product.planning.common.constant.ResourceConstants;
import com.product.planning.common.constant.StatusConstants;
import com.product.planning.common.utils.StringUtils;
import com.product.planning.domain.entity.OperationTask;
import com.product.planning.domain.entity.ProductionBatch;
import com.product.planning.domain.entity.TaskAssignment;
import com.product.planning.domain.entity.TaskAssignmentResource;
import com.product.planning.domain.entity.TaskDependency;
import com.product.planning.domain.entity.TaskResourceRequirement;
import com.product.planning.domain.model.Calendar;
import com.product.planning.domain.model.ChangeoverRule;
import com.product.planning.domain.model.Machine;
import com.product.planning.domain.model.OrderLineSnapshot;
import com.product.planning.domain.model.OrderSnapshot;
import com.product.planning.domain.model.Product;
import com.product.planning.domain.model.Resource;
import com.product.planning.domain.model.ResourceCapability;
import com.product.planning.dto.MachineLastAssignmentDTO;
import com.product.planning.dto.TaskSchedulingPriorityDTO;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 排程查询服务（Phase 4 服务化移植）。
 *
 * <p><strong>职责（与单体一致）：</strong>排程相关的数据查询与预处理。差异仅在数据通道：
 * planning 权属表（operation_task/task_resource_requirement/production_batch/
 * task_assignment/task_assignment_resource/task_dependency）仍为本库查询；跨域数据
 * （订单/订单行/产品/资源/日历/换型规则）改为 {@link SchedulingSnapshotLoader} 批量契约
 * 加载（版本化输入快照，禁止跨库/N+1，见该类 javadoc 的显式版本规则）。算法语义冻结：
 * 各方法的数据组装顺序、过滤与排序逻辑与单体逐字一致。</p>
 */
@Slf4j
@Component
public class TaskSchedulingQueryService {

    private final SchedulingSnapshotLoader snapshotLoader;

    public TaskSchedulingQueryService(SchedulingSnapshotLoader snapshotLoader) {
        this.snapshotLoader = snapshotLoader;
    }

    /**
     * 加载可用机台列表（原单体同库查询改经主数据契约；状态过滤 AVAILABLE 语义不变）。
     */
    public List<Resource> loadAvailableMachines() {
        return snapshotLoader.loadAvailableResources(List.of(ResourceConstants.RESOURCE_TYPE_MACHINE));
    }

    /**
     * 加载排程资源上下文（版本化快照）。
     *
     * <p>流程与单体一致：本地装配任务资源需求与产品上下文 → 解析所需资源类型 →
     * 契约加载可用资源 → 契约加载日历与换型规则 → 契约加载产品（换型编码输入）。</p>
     */
    public SchedulingResourceContext loadSchedulingResourceContext(List<OperationTask> tasks) {
        List<OperationTask> safeTasks = tasks == null ? List.of() : tasks;
        attachTaskResourceRequirements(safeTasks);
        attachTaskProductContext(safeTasks);
        Set<String> resourceTypes = resolveRequiredResourceTypes(safeTasks);
        List<Resource> resources = snapshotLoader.loadAvailableResources(resourceTypes);
        Map<Long, Calendar> calendarMap = loadCalendarMap(resources);
        ChangeoverRule changeoverRule = snapshotLoader.loadDefaultChangeoverRule();
        Map<Long, Product> productMap = snapshotLoader.loadProducts(safeTasks.stream()
                .map(OperationTask::getProductId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet()));
        return buildSchedulingResourceContext(resources, calendarMap, changeoverRule, productMap);
    }

    /**
     * 加载资源对应的班次日历映射（契约批量；与单体按 calendarId IN 查询语义一致）。
     */
    public Map<Long, Calendar> loadCalendarMap(List<Resource> resources) {
        Set<Long> calendarIds = new HashSet<>();
        for (Resource resource : resources) {
            if (resource != null && resource.getCalendarId() != null) {
                calendarIds.add(resource.getCalendarId());
            }
        }
        if (calendarIds.isEmpty()) {
            return new HashMap<>();
        }
        return snapshotLoader.loadCalendars(calendarIds);
    }

    /**
     * 统计 READY 状态的任务数量。
     */
    public int countReadyTasks() {
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
        List<OperationTask> normalizedTasks = normalizeReadyTasksForScheduling(readyTasks);
        attachTaskResourceRequirements(normalizedTasks);
        attachTaskProductContext(normalizedTasks);
        return normalizedTasks;
    }

    /**
     * 根据任务 ID 加载单个任务。
     */
    public OperationTask loadTaskByTaskId(Long taskId) {
        if (taskId == null) {
            return null;
        }
        return Db.lambdaQuery(OperationTask.class)
                .eq(OperationTask::getTaskId, taskId)
                .one();
    }

    /**
     * 将 READY 任务标准化为适合排程的稳定输入集合（语义冻结）。
     */
    public List<OperationTask> normalizeReadyTasksForScheduling(List<OperationTask> tasks) {
        if (CollectionUtils.isEmpty(tasks)) {
            return new ArrayList<>();
        }
        Map<Long, OperationTask> distinctTaskMap = tasks.stream()
                .filter(Objects::nonNull)
                .filter(item -> item.getTaskId() != null)
                .filter(item -> StatusConstants.READY_OPERATION_TASK.equals(item.getStatus()))
                .collect(Collectors.toMap(OperationTask::getTaskId, item -> item, (left, right) -> left,
                        LinkedHashMap::new));
        if (distinctTaskMap.isEmpty()) {
            return new ArrayList<>();
        }
        Set<Long> assignedTaskIds = loadExistingAssignmentTaskIds(new ArrayList<>(distinctTaskMap.keySet()));
        return normalizeReadyTasksForScheduling(new ArrayList<>(distinctTaskMap.values()), assignedTaskIds);
    }

    /**
     * 对外暴露纯内存标准化逻辑，便于单元测试（与单体逐字一致）。
     */
    List<OperationTask> normalizeReadyTasksForScheduling(List<OperationTask> tasks, Set<Long> assignedTaskIds) {
        if (CollectionUtils.isEmpty(tasks)) {
            return new ArrayList<>();
        }
        Set<Long> existingTaskIds = assignedTaskIds == null ? Collections.emptySet() : assignedTaskIds;
        // 過濾空，非READY,已存在派工記錄的任務
        Map<Long, OperationTask> distinctTaskMap = tasks.stream()
                .filter(Objects::nonNull)
                .filter(item -> item.getTaskId() != null)
                .filter(item -> StatusConstants.READY_OPERATION_TASK.equals(item.getStatus()))
                .filter(item -> !existingTaskIds.contains(item.getTaskId()))
                .collect(Collectors.toMap(OperationTask::getTaskId, item -> item, (left, right) -> left,
                        LinkedHashMap::new));
        if (distinctTaskMap.isEmpty()) {
            return new ArrayList<>();
        }
        List<OperationTask> orderedTasks = new ArrayList<>(distinctTaskMap.values());
        // 按任務開始時間、批次id、工序順序、任務id
        orderedTasks.sort(Comparator
                .comparing(OperationTask::getEarliestStart, Comparator.nullsLast(LocalDateTime::compareTo))
                .thenComparing(OperationTask::getBatchId, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(OperationTask::getSequence, Comparator.nullsLast(Long::compareTo))
                .thenComparing(OperationTask::getTaskId, Comparator.nullsLast(Comparator.naturalOrder())));
        return orderedTasks;
    }

    /**
     * 为任务列表批量关联资源需求（本库批量 IN 查询，与单体一致）。
     */
    void attachTaskResourceRequirements(List<OperationTask> tasks) {
        if (CollectionUtils.isEmpty(tasks)) {
            return;
        }
        List<Long> taskIds = tasks.stream()
                .map(OperationTask::getTaskId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        if (CollectionUtils.isEmpty(taskIds)) {
            return;
        }
        Map<Long, List<TaskResourceRequirement>> requirementMap = Db.lambdaQuery(TaskResourceRequirement.class)
                .in(TaskResourceRequirement::getTaskId, taskIds)
                .list()
                .stream()
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(TaskResourceRequirement::getTaskId));
        tasks.forEach(
                task -> task.setResourceRequirementList(requirementMap.getOrDefault(task.getTaskId(), List.of())));
    }

    /**
     * 为任务挂载产品上下文（task → batch 本地 + batch.orderLineId → productId 契约批量；
     * 与单体两段本地查询改为一段本地 + 一段契约，调用次数与任务数无关）。
     */
    void attachTaskProductContext(List<OperationTask> tasks) {
        if (CollectionUtils.isEmpty(tasks)) {
            return;
        }
        Set<Long> batchIds = tasks.stream()
                .map(OperationTask::getBatchId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (batchIds.isEmpty()) {
            return;
        }
        Map<Long, Long> batchToOrderLine = Db.lambdaQuery(ProductionBatch.class)
                .select(ProductionBatch::getBatchId, ProductionBatch::getOrderLineId)
                .in(ProductionBatch::getBatchId, batchIds)
                .list()
                .stream()
                .filter(Objects::nonNull)
                .filter(item -> item.getBatchId() != null && item.getOrderLineId() != null)
                .collect(Collectors.toMap(ProductionBatch::getBatchId, ProductionBatch::getOrderLineId, (left, right) -> left));
        if (batchToOrderLine.isEmpty()) {
            return;
        }
        Set<Long> orderLineIds = new HashSet<>(batchToOrderLine.values());
        Map<Long, OrderLineSnapshot> orderLineMap = snapshotLoader.loadOrderLines(orderLineIds);
        tasks.forEach(task -> {
            if (task == null || task.getBatchId() == null) {
                return;
            }
            Long orderLineId = batchToOrderLine.get(task.getBatchId());
            OrderLineSnapshot orderLine = orderLineId == null ? null : orderLineMap.get(orderLineId);
            if (orderLine != null && orderLine.getProductId() != null) {
                task.setProductId(orderLine.getProductId());
            }
        });
    }

    public ChangeoverRule loadDefaultChangeoverRule() {
        return snapshotLoader.loadDefaultChangeoverRule();
    }

    /**
     * 机台最近一次派工快照（本库 task_assignment/task_assignment_resource + 产品编码契约批量）。
     */
    public Map<Long, MachineLastAssignmentDTO> loadMachineLastAssignments(Collection<Long> machineIds) {
        if (CollectionUtils.isEmpty(machineIds)) {
            return Map.of();
        }
        List<TaskAssignment> assignments = Db.lambdaQuery(TaskAssignment.class)
                .select(TaskAssignment::getAssignmentId, TaskAssignment::getTaskId,
                        TaskAssignment::getMachineId, TaskAssignment::getPlannedEnd)
                .in(TaskAssignment::getMachineId, machineIds)
                .orderByDesc(TaskAssignment::getPlannedEnd)
                .list();
        if (CollectionUtils.isEmpty(assignments)) {
            return Map.of();
        }
        Map<Long, TaskAssignment> latestByMachine = new LinkedHashMap<>();
        for (TaskAssignment assignment : assignments) {
            if (assignment == null || assignment.getMachineId() == null) {
                continue;
            }
            latestByMachine.putIfAbsent(assignment.getMachineId(), assignment);
        }
        if (latestByMachine.isEmpty()) {
            return Map.of();
        }
        Set<Long> taskIds = latestByMachine.values().stream()
                .map(TaskAssignment::getTaskId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, Long> taskToMold = Db.lambdaQuery(TaskAssignmentResource.class)
                .select(TaskAssignmentResource::getTaskId, TaskAssignmentResource::getResourceId)
                .in(TaskAssignmentResource::getTaskId, taskIds)
                .eq(TaskAssignmentResource::getResourceType, ResourceConstants.RESOURCE_TYPE_MOLD)
                .list()
                .stream()
                .filter(Objects::nonNull)
                .filter(item -> item.getTaskId() != null && item.getResourceId() != null)
                .collect(Collectors.toMap(TaskAssignmentResource::getTaskId, TaskAssignmentResource::getResourceId,
                        (left, right) -> left));
        Map<Long, Long> taskToProduct = loadProductIdByTaskIds(taskIds);
        Map<Long, Product> productMap = snapshotLoader.loadProducts(new HashSet<>(taskToProduct.values()));
        Map<Long, MachineLastAssignmentDTO> result = new HashMap<>();
        latestByMachine.forEach((machineId, assignment) -> {
            MachineLastAssignmentDTO dto = new MachineLastAssignmentDTO();
            dto.setMachineId(machineId);
            dto.setTaskId(assignment.getTaskId());
            dto.setMoldId(taskToMold.get(assignment.getTaskId()));
            Long productId = taskToProduct.get(assignment.getTaskId());
            dto.setProductId(productId);
            Product product = productId == null ? null : productMap.get(productId);
            if (product != null) {
                dto.setMaterialCode(product.getMaterialCode());
                dto.setColorCode(product.getColorCode());
            }
            result.put(machineId, dto);
        });
        return result;
    }

    private Map<Long, Long> loadProductIdByTaskIds(Collection<Long> taskIds) {
        if (CollectionUtils.isEmpty(taskIds)) {
            return Map.of();
        }
        Map<Long, Long> taskToBatch = Db.lambdaQuery(OperationTask.class)
                .select(OperationTask::getTaskId, OperationTask::getBatchId)
                .in(OperationTask::getTaskId, taskIds)
                .list()
                .stream()
                .filter(Objects::nonNull)
                .filter(item -> item.getTaskId() != null && item.getBatchId() != null)
                .collect(Collectors.toMap(OperationTask::getTaskId, OperationTask::getBatchId, (left, right) -> left));
        if (taskToBatch.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> batchToOrderLine = Db.lambdaQuery(ProductionBatch.class)
                .select(ProductionBatch::getBatchId, ProductionBatch::getOrderLineId)
                .in(ProductionBatch::getBatchId, taskToBatch.values())
                .list()
                .stream()
                .filter(Objects::nonNull)
                .filter(item -> item.getBatchId() != null && item.getOrderLineId() != null)
                .collect(Collectors.toMap(ProductionBatch::getBatchId, ProductionBatch::getOrderLineId, (left, right) -> left));
        Set<Long> orderLineIds = new HashSet<>(batchToOrderLine.values());
        Map<Long, OrderLineSnapshot> orderLineToProduct = snapshotLoader.loadOrderLines(orderLineIds);
        Map<Long, Long> result = new HashMap<>();
        taskToBatch.forEach((taskId, batchId) -> {
            Long orderLineId = batchToOrderLine.get(batchId);
            if (orderLineId == null) {
                return;
            }
            OrderLineSnapshot orderLine = orderLineToProduct.get(orderLineId);
            if (orderLine != null && orderLine.getProductId() != null) {
                result.put(taskId, orderLine.getProductId());
            }
        });
        return result;
    }

    /**
     * 加载可用资源（契约版本化快照；单体按类型 IN + AVAILABLE 过滤语义一致）。
     */
    List<Resource> loadAvailableResources(Collection<String> resourceTypes) {
        Set<String> normalizedTypes = normalizeResourceTypes(resourceTypes);
        return snapshotLoader.loadAvailableResources(normalizedTypes);
    }

    /**
     * 为资源列表关联能力信息（纯内存装配，与单体一致；供单测复用）。
     */
    void attachResourceCapabilities(List<Resource> resources, List<ResourceCapability> capabilities) {
        if (CollectionUtils.isEmpty(resources)) {
            return;
        }
        Map<Long, List<ResourceCapability>> capabilityMap = CollectionUtils.isEmpty(capabilities)
                ? Collections.emptyMap()
                : capabilities.stream()
                        .filter(Objects::nonNull)
                        .filter(item -> item.getResourceId() != null)
                        .collect(Collectors.groupingBy(ResourceCapability::getResourceId));
        resources.forEach(resource -> {
            if (resource != null) {
                resource.setCapabilityList(capabilityMap.getOrDefault(resource.getResourceId(), List.of()));
            }
        });
    }

    SchedulingResourceContext buildSchedulingResourceContext(List<Resource> resources,
            Map<Long, Calendar> calendarMap) {
        return buildSchedulingResourceContext(resources, calendarMap, null, Map.of());
    }

    SchedulingResourceContext buildSchedulingResourceContext(List<Resource> resources,
            Map<Long, Calendar> calendarMap,
            ChangeoverRule changeoverRule,
            Map<Long, Product> productMap) {
        List<Resource> safeResources = resources == null ? List.of() : resources;
        Map<String, List<Resource>> resourcesByType = safeResources.stream()
                .filter(Objects::nonNull)
                .filter(item -> StringUtils.isNotEmpty(item.getResourceType()))
                .collect(Collectors.groupingBy(Resource::getResourceType, LinkedHashMap::new, Collectors.toList()));
        return new SchedulingResourceContext(safeResources, resourcesByType,
                calendarMap == null ? Map.of() : calendarMap,
                changeoverRule,
                productMap == null ? Map.of() : productMap);
    }

    /**
     * 解析任务列表所需资源类型（纯内存，与单体一致）。
     */
    Set<String> resolveRequiredResourceTypes(List<OperationTask> tasks) {
        Set<String> resourceTypes = new LinkedHashSet<>();
        if (CollectionUtils.isEmpty(tasks)) {
            resourceTypes.add(ResourceConstants.RESOURCE_TYPE_MACHINE);
            return resourceTypes;
        }
        tasks.stream()
                .filter(Objects::nonNull)
                .map(OperationTask::getResourceRequirementList)
                .filter(CollectionUtils::isNotEmpty)
                .flatMap(Collection::stream)
                .filter(Objects::nonNull)
                .map(TaskResourceRequirement::getResourceType)
                .filter(Objects::nonNull)
                .forEach(resourceTypes::add);
        if (resourceTypes.isEmpty()) {
            resourceTypes.add(ResourceConstants.RESOURCE_TYPE_MACHINE);
        }
        return resourceTypes;
    }

    private Set<String> normalizeResourceTypes(Collection<String> resourceTypes) {
        Set<String> normalizedTypes = new LinkedHashSet<>();
        if (CollectionUtils.isNotEmpty(resourceTypes)) {
            resourceTypes.stream()
                    .filter(Objects::nonNull)
                    .forEach(normalizedTypes::add);
        }
        if (normalizedTypes.isEmpty()) {
            normalizedTypes.add(ResourceConstants.RESOURCE_TYPE_MACHINE);
        }
        return normalizedTypes;
    }

    /**
     * 加载后置任务到前置任务 ID 的依赖映射（本库，与单体一致）。
     */
    public Map<Long, List<Long>> loadPostToPredecessorsMap(List<Long> taskIds) {
        if (CollectionUtils.isEmpty(taskIds)) {
            return Map.of();
        }
        return Db.lambdaQuery(TaskDependency.class)
                .in(TaskDependency::getPostTaskId, taskIds)
                .list()
                .stream()
                .filter(Objects::nonNull)
                .filter(item -> item.getPostTaskId() != null
                        && item.getPreTaskId() != null)
                .collect(Collectors.groupingBy(
                        TaskDependency::getPostTaskId,
                        Collectors.mapping(TaskDependency::getPreTaskId, Collectors.toList())));
    }

    /**
     * 批量加载任务的 planned_end，用于依赖约束（本库，与单体一致）。
     */
    public Map<Long, LocalDateTime> loadPlannedEndByTaskIds(Collection<Long> taskIds) {
        if (CollectionUtils.isEmpty(taskIds)) {
            return Map.of();
        }
        return Db.lambdaQuery(TaskAssignment.class)
                .select(TaskAssignment::getTaskId, TaskAssignment::getPlannedEnd)
                .in(TaskAssignment::getTaskId, taskIds)
                .list()
                .stream()
                .filter(Objects::nonNull)
                .filter(item -> item.getTaskId() != null && item.getPlannedEnd() != null)
                .collect(Collectors.toMap(
                        TaskAssignment::getTaskId,
                        TaskAssignment::getPlannedEnd,
                        (left, right) -> left.isAfter(right) ? left : right));
    }

    /**
     * 查询已存在派工记录的任务 ID，用于排程前去重（本库，与单体一致）。
     */
    private Set<Long> loadExistingAssignmentTaskIds(List<Long> taskIds) {
        if (CollectionUtils.isEmpty(taskIds)) {
            return Collections.emptySet();
        }
        return Db.lambdaQuery(TaskAssignment.class)
                .select(TaskAssignment::getTaskId)
                .in(TaskAssignment::getTaskId, taskIds)
                .list()
                .stream()
                .map(TaskAssignment::getTaskId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));
    }

    /**
     * 加载任务的优先级上下文（交期 + 优先级）。
     *
     * <p>数据链路与单体一致：OperationTask → ProductionBatch（本库）→ OrderLine →
     * CustomerOrder（需求契约批量）；从订单提取 dueDate/priority 供 DUE_DATE_PRIORITY
     * 策略排序。契约调用次数与任务数无关（本库 1 次 + 需求域 2 次）。</p>
     */
    public Map<Long, TaskSchedulingPriorityDTO> loadTaskPriorityMap(List<OperationTask> tasks) {
        if (CollectionUtils.isEmpty(tasks)) {
            return new HashMap<>();
        }

        // 步骤1：提取所有 batchId（任务 → 批次）
        Set<Long> batchIds = tasks.stream()
                .map(OperationTask::getBatchId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (batchIds.isEmpty()) {
            return new HashMap<>();
        }

        // 步骤2：批量查询批次（本库），获取批次与订单行的映射
        List<ProductionBatch> batches = Db.lambdaQuery(ProductionBatch.class)
                .select(ProductionBatch::getBatchId, ProductionBatch::getOrderLineId)
                .in(ProductionBatch::getBatchId, batchIds)
                .list();
        if (CollectionUtils.isEmpty(batches)) {
            return new HashMap<>();
        }

        // 构建映射：batchId → orderLineId
        Map<Long, Long> batchToOrderLine = batches.stream()
                .filter(Objects::nonNull)
                .filter(item -> item.getBatchId() != null && item.getOrderLineId() != null)
                .collect(Collectors.toMap(ProductionBatch::getBatchId, ProductionBatch::getOrderLineId, (a, b) -> a));

        // 步骤3：契约批量查询订单行，获取订单行与订单的映射
        Set<Long> orderLineIds = new HashSet<>(batchToOrderLine.values());
        Map<Long, OrderLineSnapshot> orderLines = snapshotLoader.loadOrderLines(orderLineIds);
        // 构建映射：orderLineId → orderId
        Map<Long, Long> orderLineToOrderId = orderLines.values().stream()
                .filter(item -> item.getOrderLineId() != null && item.getOrderId() != null)
                .collect(Collectors.toMap(OrderLineSnapshot::getOrderLineId, OrderLineSnapshot::getOrderId, (a, b) -> a));

        // 步骤4：契约批量查询订单，获取订单详情（交期、优先级）
        Set<Long> orderIds = new HashSet<>(orderLineToOrderId.values());
        Map<Long, OrderSnapshot> orderMap = snapshotLoader.loadOrders(orderIds);

        // 步骤5：组装最终结果，遍历任务建立完整的优先级上下文
        Map<Long, TaskSchedulingPriorityDTO> priorityMap = new HashMap<>();
        for (OperationTask task : tasks) {
            if (task == null || task.getTaskId() == null) {
                continue;
            }
            TaskSchedulingPriorityDTO context = new TaskSchedulingPriorityDTO();
            context.setTaskId(task.getTaskId());
            context.setBatchId(task.getBatchId());

            // 链式查找：task.batchId → orderLineId → orderId → order
            Long orderLineId = task.getBatchId() == null ? null : batchToOrderLine.get(task.getBatchId());
            if (orderLineId != null) {
                Long orderId = orderLineToOrderId.get(orderLineId);
                if (orderId != null) {
                    OrderSnapshot order = orderMap.get(orderId);
                    if (order != null) {
                        context.setDueDate(order.getDueDate());
                        context.setPriority(order.getPriority());
                    }
                }
            }
            priorityMap.put(task.getTaskId(), context);
        }
        return priorityMap;
    }

    public static class SchedulingResourceContext {
        private final List<Resource> resources;
        private final Map<String, List<Resource>> resourcesByType;
        private final Map<Long, Calendar> calendarMap;
        private final ChangeoverRule changeoverRule;
        private final Map<Long, Product> productMap;

        public SchedulingResourceContext(List<Resource> resources,
                Map<String, List<Resource>> resourcesByType,
                Map<Long, Calendar> calendarMap,
                ChangeoverRule changeoverRule,
                Map<Long, Product> productMap) {
            this.resources = resources;
            this.resourcesByType = resourcesByType;
            this.calendarMap = calendarMap;
            this.changeoverRule = changeoverRule;
            this.productMap = productMap;
        }

        public List<Resource> getResources() {
            return resources;
        }

        public Map<String, List<Resource>> getResourcesByType() {
            return resourcesByType;
        }

        public Map<Long, Calendar> getCalendarMap() {
            return calendarMap;
        }

        public ChangeoverRule getChangeoverRule() {
            return changeoverRule;
        }

        public Map<Long, Product> getProductMap() {
            return productMap;
        }
    }
}
