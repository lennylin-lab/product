package com.product.pps.service.impl;

import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.product.common.constant.ResourceConstants;
import com.product.common.constant.StatusConstants;
import com.product.common.utils.StringUtils;
import com.product.domain.entity.ChangeoverRule;
import com.product.domain.entity.CustomerOrder;
import com.product.domain.entity.Calendar;
import com.product.domain.entity.Machine;
import com.product.domain.entity.MachineMoldCompatibility;
import com.product.domain.entity.OperationTask;
import com.product.domain.entity.OrderLine;
import com.product.domain.entity.Product;
import com.product.domain.entity.ProductionBatch;
import com.product.domain.entity.Resource;
import com.product.domain.entity.ResourceCapability;
import com.product.domain.entity.TaskAssignment;
import com.product.domain.entity.TaskAssignmentResource;
import com.product.domain.entity.TaskDependency;
import com.product.domain.entity.TaskResourceRequirement;
import com.product.pps.dto.MachineLastAssignmentDTO;
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
        return loadAvailableResourcesByType(List.of(ResourceConstants.RESOURCE_TYPE_MACHINE))
                .getOrDefault(ResourceConstants.RESOURCE_TYPE_MACHINE, List.of());
    }

    /**
     * 加载排程资源上下文。
     *
     * <p>
     * 输出统一的资源分组、能力矩阵与日历视图，供后续 Calculator 扩展多资源排程时直接消费。
     * 当前默认总是包含 MACHINE，同时按任务资源需求补充 PERSON / WORKSTATION 等类型。
     * </p>
     */
    public SchedulingResourceContext loadSchedulingResourceContext(List<OperationTask> tasks) {
        List<OperationTask> safeTasks = tasks == null ? List.of() : tasks;
        attachTaskResourceRequirements(safeTasks);
        attachTaskProductContext(safeTasks);
        Set<String> resourceTypes = resolveRequiredResourceTypes(safeTasks);
        List<Resource> resources = loadAvailableResources(resourceTypes);
        Map<Long, Calendar> calendarMap = loadCalendarMap(resources);
        ChangeoverRule changeoverRule = loadDefaultChangeoverRule();
        Map<Long, Product> productMap = loadProductsByIds(safeTasks.stream()
                .map(OperationTask::getProductId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet()));
        return buildSchedulingResourceContext(resources, calendarMap, changeoverRule, productMap);
    }

    /**
     * 按资源类型加载可用资源，并挂载能力矩阵与机台扩展信息。
     */
    public Map<String, List<Resource>> loadAvailableResourcesByType(Collection<String> resourceTypes) {
        List<Resource> resources = loadAvailableResources(resourceTypes);
        return buildSchedulingResourceContext(resources, loadCalendarMap(resources)).getResourcesByType();
    }

    /**
     * 加载资源对应的班次日历映射。
     *
     * <p>
     * 根据资源列表提取所有 calendarId，批量查询对应的 Calendar 实体，
     * 并以 calendarId 为键建立 Map，方便后续根据资源快速查找日历。
     * </p>
     *
     * @param resources 资源列表
     * @return calendarId → Calendar 的映射，不会返回 null
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
        List<OperationTask> normalizedTasks = normalizeReadyTasksForScheduling(readyTasks);
        attachTaskResourceRequirements(normalizedTasks);
        attachTaskProductContext(normalizedTasks);
        return normalizedTasks;
    }

    /**
     * 根据任务 ID 加载单个任务。
     *
     * @param taskId 任务唯一标识
     * @return 任务实体，找不到或 taskId 为空时返回 null
     */
    public OperationTask loadTaskByTaskId(Long taskId) {
        // 参数校验：taskId 为空时直接返回 null，避免无效查询
        if (taskId == null) {
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
     * 对外暴露纯内存标准化逻辑，便于单元测试。
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
     * 为任务列表批量关联资源需求
     * 1. 检查任务列表是否为空
     * 2. 提取所有有效的 taskId
     * 3. 批量查询资源需求并按 taskId 分组
     * 4. 为每个任务设置对应的资源需求列表
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
        Map<Long, OrderLine> orderLineMap = Db.lambdaQuery(OrderLine.class)
                .in(OrderLine::getOrderLineId, orderLineIds)
                .list()
                .stream()
                .filter(Objects::nonNull)
                .filter(item -> item.getOrderLineId() != null)
                .collect(Collectors.toMap(OrderLine::getOrderLineId, item -> item, (left, right) -> left));
        tasks.forEach(task -> {
            if (task == null || task.getBatchId() == null) {
                return;
            }
            Long orderLineId = batchToOrderLine.get(task.getBatchId());
            OrderLine orderLine = orderLineId == null ? null : orderLineMap.get(orderLineId);
            if (orderLine != null && orderLine.getProductId() != null) {
                task.setProductId(orderLine.getProductId());
            }
        });
    }

    public ChangeoverRule loadDefaultChangeoverRule() {
        return Db.lambdaQuery(ChangeoverRule.class)
                .last("limit 1")
                .one();
    }

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
        Map<Long, Product> productMap = loadProductsByIds(new HashSet<>(taskToProduct.values()));
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
        Map<Long, Long> orderLineToProduct = Db.lambdaQuery(OrderLine.class)
                .select(OrderLine::getOrderLineId, OrderLine::getProductId)
                .in(OrderLine::getOrderLineId, orderLineIds)
                .list()
                .stream()
                .filter(Objects::nonNull)
                .filter(item -> item.getOrderLineId() != null && item.getProductId() != null)
                .collect(Collectors.toMap(OrderLine::getOrderLineId, OrderLine::getProductId, (left, right) -> left));
        Map<Long, Long> result = new HashMap<>();
        taskToBatch.forEach((taskId, batchId) -> {
            Long orderLineId = batchToOrderLine.get(batchId);
            if (orderLineId == null) {
                return;
            }
            Long productId = orderLineToProduct.get(orderLineId);
            if (productId != null) {
                result.put(taskId, productId);
            }
        });
        return result;
    }

    private Map<Long, Product> loadProductsByIds(Set<Long> productIds) {
        if (CollectionUtils.isEmpty(productIds)) {
            return Map.of();
        }
        return Db.lambdaQuery(Product.class)
                .in(Product::getProductId, productIds)
                .list()
                .stream()
                .filter(Objects::nonNull)
                .filter(item -> item.getProductId() != null)
                .collect(Collectors.toMap(Product::getProductId, item -> item, (left, right) -> left));
    }

    /**
     * 加载可用的资源列表
     * 1. 规范化资源类型（确保包含机器类型）
     * 2. 查询指定类型且状态为可用的资源
     * 3. 关联机器详情和资源能力信息
     * 4. 返回可用的资源列表
     */
    List<Resource> loadAvailableResources(Collection<String> resourceTypes) {
        Set<String> normalizedTypes = normalizeResourceTypes(resourceTypes);
        List<Resource> resources = Db.lambdaQuery(Resource.class)
                .in(Resource::getResourceType, normalizedTypes)
                .eq(Resource::getStatus, StatusConstants.AVAILABLE_RESOURCE_STATUS)
                .list();
        if (CollectionUtils.isEmpty(resources)) {
            return new ArrayList<>();
        }
        List<Resource> availableResources = resources.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        // 为每个资源设置对应的机器详情和模具兼容性列表
        attachMachineDetails(availableResources);
        attachResourceCapabilities(availableResources);
        return availableResources;
    }

    /**
     * 为资源列表关联机器详情
     * 1. 提取所有有效的 machineId
     * 2. 批量查询机器信息并转换为 Map
     * 3. 批量查询模具兼容性并按 machineId 分组
     * 4. 为每个资源设置对应的机器详情和模具兼容性列表
     */
    private void attachMachineDetails(List<Resource> machines) {
        if (CollectionUtils.isEmpty(machines)) {
            return;
        }
        List<Long> machineIds = machines.stream()
                .map(Resource::getResourceId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        if (CollectionUtils.isEmpty(machineIds)) {
            return;
        }
        Map<Long, Machine> machineMap = Db.lambdaQuery(Machine.class)
                .in(Machine::getMachineId, machineIds)
                .list()
                .stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(Machine::getMachineId, item -> item, (left, right) -> left));
        Map<Long, List<MachineMoldCompatibility>> compatibilityMap = Db.lambdaQuery(MachineMoldCompatibility.class)
                .in(MachineMoldCompatibility::getMachineId, machineIds)
                .list()
                .stream()
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(MachineMoldCompatibility::getMachineId));
        machines.forEach(machineResource -> {
            Machine machine = machineMap.get(machineResource.getResourceId());
            if (machine == null) {
                return;
            }
            machine.setMoldCompatibilityList(compatibilityMap.getOrDefault(machine.getMachineId(), List.of()));
            machineResource.setMachine(machine);
        });
    }

    /**
     * 为资源列表关联能力信息（查询入口）
     * 1. 提取所有有效的 resourceId
     * 2. 批量查询资源能力
     * 3. 调用重载方法完成关联
     */
    void attachResourceCapabilities(List<Resource> resources) {
        if (CollectionUtils.isEmpty(resources)) {
            return;
        }
        List<Long> resourceIds = resources.stream()
                .map(Resource::getResourceId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        if (CollectionUtils.isEmpty(resourceIds)) {
            return;
        }
        List<ResourceCapability> capabilities = Db.lambdaQuery(ResourceCapability.class)
                .in(ResourceCapability::getResourceId, resourceIds)
                .list();
        attachResourceCapabilities(resources, capabilities);
    }

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
     * 解析任务列表所需资源类型
     * 1. 初始化包含机器资源的集合
     * 2. 遍历每个任务的资源需求，提取资源类型并去重
     * 3. 返回所有需要的资源类型集合
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
     * 加载后置任务到前置任务 ID 的依赖映射。
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
     * 批量加载任务的 planned_end，用于依赖约束。
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
     * 查询已存在派工记录的任务 ID，用于排程前去重，避免重复派工。
     *
     * <p>
     * 通过 IN 查询批量检查任务是否已有派工记录，返回有派工记录的任务 ID 集合。
     * </p>
     *
     * @param taskIds 待检查的任务 ID 列表
     * @return 已有派工记录的任务 ID 集合，不会返回 null
     */
    private Set<Long> loadExistingAssignmentTaskIds(List<Long> taskIds) {
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
                .filter(Objects::nonNull) // 过滤空字符串
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
    public Map<Long, TaskSchedulingPriorityDTO> loadTaskPriorityMap(List<OperationTask> tasks) {
        if (CollectionUtils.isEmpty(tasks)) {
            return new HashMap<>();
        }

        // 步骤1：提取所有 batchId（任务 → 批次）
        Set<Long> batchIds = tasks.stream()
                .map(OperationTask::getBatchId) // 提取 batchId
                .filter(Objects::nonNull) // 过滤空值
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
        Map<Long, Long> batchToOrderLine = batches.stream()
                .filter(Objects::nonNull)
                .filter(item -> item.getBatchId() != null && item.getOrderLineId() != null)
                .collect(Collectors.toMap(ProductionBatch::getBatchId, ProductionBatch::getOrderLineId, (a, b) -> a));

        // 步骤3：批量查询订单行，获取订单行与订单的映射
        Set<Long> orderLineIds = new HashSet<>(batchToOrderLine.values());
        List<OrderLine> orderLines = Db.lambdaQuery(OrderLine.class)
                .select(OrderLine::getOrderLineId, OrderLine::getOrderId)
                .in(OrderLine::getOrderLineId, orderLineIds)
                .list();
        // 构建映射：orderLineId → orderId
        Map<Long, Long> orderLineToOrderId = orderLines.stream()
                .filter(Objects::nonNull)
                .filter(item -> item.getOrderLineId() != null && item.getOrderId() != null)
                .collect(Collectors.toMap(OrderLine::getOrderLineId, OrderLine::getOrderId, (a, b) -> a));

        // 步骤4：批量查询订单，获取订单详情（交期、优先级）
        Set<Long> orderIds = new HashSet<>(orderLineToOrderId.values());
        List<CustomerOrder> orders = Db.lambdaQuery(CustomerOrder.class)
                .select(CustomerOrder::getOrderId, CustomerOrder::getDueDate, CustomerOrder::getPriority)
                .in(CustomerOrder::getOrderId, orderIds)
                .list();
        // 构建映射：orderId → CustomerOrder
        Map<Long, CustomerOrder> orderMap = orders.stream()
                .filter(Objects::nonNull)
                .filter(item -> item.getOrderId() != null)
                .collect(Collectors.toMap(CustomerOrder::getOrderId, item -> item, (a, b) -> a));

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
