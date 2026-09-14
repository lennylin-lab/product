package com.product.planning.service.impl;

import com.product.planning.common.constant.ResourceConstants;
import com.product.planning.common.constant.RouteOperationConstants;
import com.product.planning.common.exception.ServiceException;
import com.product.planning.common.utils.StringUtils;
import com.product.planning.domain.model.Calendar;
import com.product.planning.domain.model.ChangeoverRule;
import com.product.planning.domain.model.Machine;
import com.product.planning.domain.model.MachineMoldCompatibility;
import com.product.planning.domain.entity.OperationTask;
import com.product.planning.domain.model.Product;
import com.product.planning.domain.model.Resource;
import com.product.planning.domain.model.ResourceCapability;
import com.product.planning.domain.entity.TaskAssignment;
import com.product.planning.domain.entity.TaskResourceRequirement;
import com.product.planning.dto.MachineLastAssignmentDTO;
import com.product.planning.dto.ResourceRuntimeStatsDTO;
import com.product.planning.dto.TaskSchedulingPriorityDTO;
import com.product.planning.mapper.TaskAssignmentResourceMapper;
import com.product.planning.enums.SchedulingStrategy;
import com.product.planning.route.RouteRuleRegistry;
import com.product.planning.route.rule.RouteEligibleResourceRule;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 排程计算器。
 *
 * 说明：这里只保留纯排程计算逻辑和资源运行时快照构建，
 * 让 TaskAssignmentServiceImpl 只负责编排、事务和持久化。
 */
@Slf4j
@Component
public class TaskSchedulingCalculator {
    @Autowired
    private TaskAssignmentResourceMapper taskAssignmentResourceMapper;
    @Autowired
    private ChangeoverCalculator changeoverCalculator;
    @Autowired
    private RouteRuleRegistry routeRuleRegistry;

    // ========================== 公共 API（Coordinator 调用入口）
    // ==========================

    public ScheduleBatchResult calculateBatchAssignments(List<OperationTask> tasks,
            TaskSchedulingQueryService.SchedulingResourceContext schedulingContext,
            ResourceRuntimeContext runtimeContext,
            LocalDateTime assignmentStart,
            SchedulingStrategy strategy) {
        return calculateBatchAssignments(tasks, schedulingContext, runtimeContext, assignmentStart, strategy,
                Map.of(), new HashMap<>());
    }

    /**
     * 计算一批任务的资源分配方案（核心排程算法）。
     *
     * <p>
     * 算法策略：贪心算法 -- 级联选择 MACHINE -> MOLD -> PERSON。
     * 每个任务选择当前最早可用的资源组合，选择后立即更新内存快照。
     * </p>
     *
     * @param tasks                 待排程的任务列表（按优先级排序）
     * @param schedulingContext     排程资源上下文（机台/模具/人员 + 日历）
     * @param runtimeContext        资源运行时上下文（内存快照，会被修改）
     * @param assignmentStart       排程开始时间基准
     * @param strategy              排程策略
     * @param postToPredecessors    后置任务 → 前置任务 ID 列表
     * @param predecessorEndTimes   前置/已排任务 planned_end（会被本批次更新）
     * @return 批次排程结果
     * @throws ServiceException 如果某个任务没有可用资源
     */
    public ScheduleBatchResult calculateBatchAssignments(List<OperationTask> tasks,
            TaskSchedulingQueryService.SchedulingResourceContext schedulingContext,
            ResourceRuntimeContext runtimeContext,
            LocalDateTime assignmentStart,
            SchedulingStrategy strategy,
            Map<Long, List<Long>> postToPredecessors,
            Map<Long, LocalDateTime> predecessorEndTimes) {
        List<TaskAssignment> assignments = new ArrayList<>(tasks.size());
        List<Long> taskIds = new ArrayList<>(tasks.size());
        Map<Long, List<Long>> safePostToPredecessors = postToPredecessors == null ? Map.of() : postToPredecessors;
        Map<Long, LocalDateTime> safePredecessorEndTimes = predecessorEndTimes == null
                ? new HashMap<>()
                : predecessorEndTimes;

        for (OperationTask task : tasks) {
            if (task == null) {
                continue;
            }

            LocalDateTime dependencyEarliestStart = resolveDependencyEarliestStart(
                    task.getTaskId(), safePostToPredecessors, safePredecessorEndTimes);
            LocalDateTime effectiveEarliestStart = maxTime(task.getEarliestStart(), dependencyEarliestStart);

            // 级联选择：MACHINE -> MOLD -> PERSON 或 WORKSTATION -> PERSON
            ResourceChoice choice = chooseResources(task, schedulingContext, runtimeContext, assignmentStart, strategy,
                    effectiveEarliestStart);
            if (choice == null) {
                throw new ServiceException("任务" + task.getTaskId() + "没有可用资源");
            }

            // 创建派工记录
            TaskAssignment assignment = new TaskAssignment();
            assignment.setTaskId(task.getTaskId());
            assignment.setMachineId(choice.machineId);
            assignment.setMoldId(choice.moldId);
            assignment.setPersonId(choice.personId);
            assignment.setWorkstationId(choice.workstationId);
            assignment.setPlannedStart(choice.plannedStart);
            assignment.setPlannedEnd(choice.plannedEnd);
            assignment.setSequenceOnResource(choice.sequenceOnResource);
            assignment.setChangeoverTimeMin(choice.changeoverTimeMin);
            assignment.setChangeoverSourceTaskId(choice.changeoverSourceTaskId);

            // 将选中的资源 ID 回写到需求列表，供持久化服务生成 TaskAssignmentResource 明细
            List<TaskResourceRequirement> resolvedRequirements = resolveSelectedResources(
                    task.getResourceRequirementList(), choice.machineId, choice.moldId, choice.personId,
                    choice.workstationId, choice.changeoverTimeMin);
            assignment.setResourceRequirementList(resolvedRequirements);

            // 记录各资源类型的序号，供持久化服务写入 TaskAssignmentResource
            Map<String, Long> resourceSequenceMap = new HashMap<>();
            if (choice.sequenceOnResource != null) {
                resourceSequenceMap.put(ResourceConstants.RESOURCE_TYPE_MACHINE, choice.sequenceOnResource);
            }
            if (choice.moldSequence != null) {
                resourceSequenceMap.put(ResourceConstants.RESOURCE_TYPE_MOLD, choice.moldSequence);
            }
            if (choice.personSequence != null) {
                resourceSequenceMap.put(ResourceConstants.RESOURCE_TYPE_PERSON, choice.personSequence);
            }
            if (choice.workstationSequence != null) {
                resourceSequenceMap.put(ResourceConstants.RESOURCE_TYPE_WORKSTATION, choice.workstationSequence);
            }
            assignment.setResourceSequenceMap(resourceSequenceMap);
            assignments.add(assignment);
            taskIds.add(task.getTaskId());
            safePredecessorEndTimes.put(task.getTaskId(), choice.plannedEnd);

            // 更新内存快照：更新所有选中资源的状态
            if (choice.machineId != null) {
                runtimeContext.update(ResourceConstants.RESOURCE_TYPE_MACHINE,
                        choice.machineId, choice.plannedEnd, choice.sequenceOnResource);
                runtimeContext.setMachineLastAssignment(choice.machineId,
                        buildMachineSnapshot(task, choice, schedulingContext));
            }
            if (choice.moldId != null) {
                runtimeContext.update(ResourceConstants.RESOURCE_TYPE_MOLD,
                        choice.moldId, choice.plannedEnd, choice.moldSequence);
            }
            if (choice.personId != null) {
                runtimeContext.update(ResourceConstants.RESOURCE_TYPE_PERSON,
                        choice.personId, choice.plannedEnd, choice.personSequence);
            }
            if (choice.workstationId != null) {
                runtimeContext.update(ResourceConstants.RESOURCE_TYPE_WORKSTATION,
                        choice.workstationId, choice.plannedEnd, choice.workstationSequence);
            }
        }
        return new ScheduleBatchResult(assignments, taskIds);
    }

    private ChangeoverCalculator.MachineAssignmentSnapshot buildMachineSnapshot(OperationTask task,
            ResourceChoice choice,
            TaskSchedulingQueryService.SchedulingResourceContext schedulingContext) {
        Product product = task == null || task.getProductId() == null || schedulingContext == null
                ? null
                : schedulingContext.getProductMap().get(task.getProductId());
        return new ChangeoverCalculator.MachineAssignmentSnapshot(
                choice.moldId,
                task == null ? null : task.getProductId(),
                product == null ? null : product.getMaterialCode(),
                product == null ? null : product.getColorCode(),
                task == null ? null : task.getTaskId());
    }

    /**
     * 根据 task_dependency 计算后置任务的依赖约束开始时间。
     */
    LocalDateTime resolveDependencyEarliestStart(Long taskId,
            Map<Long, List<Long>> postToPredecessors,
            Map<Long, LocalDateTime> predecessorEndTimes) {
        if (taskId == null || postToPredecessors == null || predecessorEndTimes == null) {
            return null;
        }
        List<Long> preTaskIds = postToPredecessors.get(taskId);
        if (CollectionUtils.isEmpty(preTaskIds)) {
            return null;
        }
        LocalDateTime latestPredecessorEnd = null;
        for (Long preTaskId : preTaskIds) {
            LocalDateTime plannedEnd = predecessorEndTimes.get(preTaskId);
            if (plannedEnd == null) {
                continue;
            }
            if (latestPredecessorEnd == null || plannedEnd.isAfter(latestPredecessorEnd)) {
                latestPredecessorEnd = plannedEnd;
            }
        }
        return latestPredecessorEnd;
    }

    /**
     * 按策略对任务排序。
     */
    public List<OperationTask> orderTasks(List<OperationTask> tasks,
            SchedulingStrategy strategy,
            Map<Long, TaskSchedulingPriorityDTO> priorityMap) {
        if (CollectionUtils.isEmpty(tasks)) {
            return new ArrayList<>();
        }
        List<OperationTask> ordered = new ArrayList<>(tasks);
        ordered.sort(taskComparator(strategy, priorityMap));
        return ordered;
    }

    // ========================== ResourceRuntimeContext 构建与操作
    // ==========================

    /**
     * 构建多资源运行时上下文（内存快照）。
     *
     * <p>
     * 通过 task_assignment_resource 表预加载所有资源类型的运行时状态，
     * 按 (resourceType, resourceId) 维度统计 max(plannedEnd) 和 max(sequenceOnResource)。
     * </p>
     *
     * @param schedulingContext 排程资源上下文
     * @return 多资源运行时上下文
     */
    public ResourceRuntimeContext buildResourceRuntimeContext(
            TaskSchedulingQueryService.SchedulingResourceContext schedulingContext) {
        return buildResourceRuntimeContext(schedulingContext, Map.of());
    }

    public ResourceRuntimeContext buildResourceRuntimeContext(
            TaskSchedulingQueryService.SchedulingResourceContext schedulingContext,
            Map<Long, MachineLastAssignmentDTO> machineLastAssignments) {
        ResourceRuntimeContext context = new ResourceRuntimeContext();
        Set<String> resourceTypes = schedulingContext.getResourcesByType().keySet();
        if (CollectionUtils.isEmpty(resourceTypes)) {
            seedMachineLastAssignments(context, schedulingContext, machineLastAssignments);
            return context;
        }

        List<ResourceRuntimeStatsDTO> stats = taskAssignmentResourceMapper.selectResourceRuntimeStats(
                new ArrayList<>(resourceTypes));
        if (CollectionUtils.isNotEmpty(stats)) {
            for (ResourceRuntimeStatsDTO row : stats) {
                if (row == null || StringUtils.isEmpty(row.getResourceType())
                        || row.getResourceId() == null) {
                    continue;
                }
                if (row.getLatestEndTime() != null) {
                    context.setNextAvailableTime(row.getResourceType(), row.getResourceId(), row.getLatestEndTime());
                }
                if (row.getMaxSequence() != null) {
                    context.setNextSequence(row.getResourceType(), row.getResourceId(), row.getMaxSequence() + 1L);
                }
            }
        }
        seedMachineLastAssignments(context, schedulingContext, machineLastAssignments);
        return context;
    }

    private void seedMachineLastAssignments(ResourceRuntimeContext context,
            TaskSchedulingQueryService.SchedulingResourceContext schedulingContext,
            Map<Long, MachineLastAssignmentDTO> machineLastAssignments) {
        if (machineLastAssignments == null || machineLastAssignments.isEmpty()) {
            return;
        }
        machineLastAssignments.forEach((machineId, snapshot) -> {
            if (snapshot == null) {
                return;
            }
            context.setMachineLastAssignment(machineId, new ChangeoverCalculator.MachineAssignmentSnapshot(
                    snapshot.getMoldId(),
                    snapshot.getProductId(),
                    snapshot.getMaterialCode(),
                    snapshot.getColorCode(),
                    snapshot.getTaskId()));
        });
    }

    // ========================== 级联资源选择（核心算法） ==========================

    /**
     * 级联选择资源：MACHINE -> MOLD -> PERSON。
     *
     * <p>
     * 选择顺序：
     * 1. 先选最优机台（贪心算法，最早可用）
     * 2. 根据任务 MOLD 需求从机台兼容模具中选择最早可用的模具
     * 3. 根据任务 PERSON 需求通过 opCode 匹配选择最早可用的人员
     * </p>
     * <p>
     * plannedStart = max(机台可用, 模具可用, 人员可用, earliestStart, assignmentStart)
     * </p>
     */
    private ResourceChoice chooseResources(OperationTask task,
            TaskSchedulingQueryService.SchedulingResourceContext schedulingContext,
            ResourceRuntimeContext runtimeContext,
            LocalDateTime assignmentStart,
            SchedulingStrategy strategy,
            LocalDateTime effectiveEarliestStart) {
        if (requiresWorkstation(task) && !requiresMachine(task)) {
            return chooseWorkstationResources(task, schedulingContext, runtimeContext, assignmentStart,
                    effectiveEarliestStart);
        }
        List<Resource> machines = schedulingContext.getResourcesByType()
                .getOrDefault(ResourceConstants.RESOURCE_TYPE_MACHINE, List.of());
        Map<Long, Calendar> calendarMap = schedulingContext.getCalendarMap();

        // 阶段1: 选择最优机台
        MachineChoice machineChoice = chooseBestMachine(task, machines, calendarMap, runtimeContext, assignmentStart,
                strategy, effectiveEarliestStart, schedulingContext);
        if (machineChoice == null) {
            return null;
        }

        Long moldId = null;
        Long moldSequence = null;
        Long personId = null;
        Long personSequence = null;

        // 阶段2: 选择模具（从机台兼容模具中选最早可用）
        if (CollectionUtils.isNotEmpty(task.getResourceRequirementList())) {
            List<TaskResourceRequirement> moldReqs = task.getResourceRequirementList().stream()
                    .filter(req -> req != null && ResourceConstants.RESOURCE_TYPE_MOLD.equals(req.getResourceType()))
                    .filter(this::isMandatoryRequirement)
                    .toList();
            if (!moldReqs.isEmpty()) {
                moldId = chooseMold(moldReqs, machineChoice.machineResource, runtimeContext, schedulingContext);
                if (moldId == null) {
                    log.warn("任务 {} 没有可用模具(机台 {})", task.getTaskId(), machineChoice.machineId);
                    return null;
                }
                moldSequence = runtimeContext.getNextSequence(ResourceConstants.RESOURCE_TYPE_MOLD, moldId);
            }
        }

        // 阶段3: 选择人员（通过 opCode 匹配技能）
        if (CollectionUtils.isNotEmpty(task.getResourceRequirementList())) {
            List<TaskResourceRequirement> personReqs = task.getResourceRequirementList().stream()
                    .filter(req -> req != null && ResourceConstants.RESOURCE_TYPE_PERSON.equals(req.getResourceType()))
                    .filter(this::isMandatoryRequirement)
                    .toList();
            if (!personReqs.isEmpty()) {
                personId = choosePerson(personReqs, task, schedulingContext, runtimeContext);
                if (personId == null) {
                    log.warn("任务 {} 没有可用人员", task.getTaskId());
                    return null;
                }
                personSequence = runtimeContext.getNextSequence(ResourceConstants.RESOURCE_TYPE_PERSON, personId);
            }
        }

        // 计算 plannedStart = max(所有资源可用时间, earliestStart, assignmentStart)
        LocalDateTime moldNextTime = moldId != null
                ? runtimeContext.getNextAvailableTime(ResourceConstants.RESOURCE_TYPE_MOLD, moldId)
                : null;
        LocalDateTime personNextTime = personId != null
                ? runtimeContext.getNextAvailableTime(ResourceConstants.RESOURCE_TYPE_PERSON, personId)
                : null;

        LocalDateTime effectiveStart = maxTime(
                machineChoice.plannedStart, moldNextTime, personNextTime,
                effectiveEarliestStart, assignmentStart);

        // 如果 effectiveStart 晚于 machineChoice.plannedStart，需要重新计算时间窗口
        LocalDateTime plannedStart;
        LocalDateTime plannedEnd;
        if (effectiveStart != null && effectiveStart.isAfter(machineChoice.plannedStart)) {
            // 需要基于新的 effectiveStart 重新计算机台时间窗口
            Calendar calendar = calendarMap.get(machineChoice.machineResource.getCalendarId());
            plannedStart = adjustToShiftStart(calendar, effectiveStart);
            long duration = task.getStdDurationMin() == null ? 0L : task.getStdDurationMin();
            TimeWindow window = adjustForShiftEnd(calendar, plannedStart, duration);
            if (window == null) {
                return null;
            }
            plannedStart = window.start;
            plannedEnd = window.end;
        } else {
            plannedStart = machineChoice.plannedStart;
            plannedEnd = machineChoice.plannedEnd;
        }

        return new ResourceChoice(
                machineChoice.machineId, plannedStart, plannedEnd,
                machineChoice.sequenceOnResource, machineChoice.setupCostMin,
                machineChoice.changeoverTimeMin, machineChoice.changeoverSourceTaskId,
                moldId, moldSequence, personId, personSequence, null, null);
    }

    private ResourceChoice chooseWorkstationResources(OperationTask task,
            TaskSchedulingQueryService.SchedulingResourceContext schedulingContext,
            ResourceRuntimeContext runtimeContext,
            LocalDateTime assignmentStart,
            LocalDateTime effectiveEarliestStart) {
        List<Resource> workstations = schedulingContext.getResourcesByType()
                .getOrDefault(ResourceConstants.RESOURCE_TYPE_WORKSTATION, List.of());
        Map<Long, Calendar> calendarMap = schedulingContext.getCalendarMap();

        WorkstationChoice workstationChoice = chooseBestWorkstation(task, workstations, calendarMap, runtimeContext,
                assignmentStart, effectiveEarliestStart);
        if (workstationChoice == null) {
            return null;
        }

        Long personId = null;
        Long personSequence = null;
        if (CollectionUtils.isNotEmpty(task.getResourceRequirementList())) {
            List<TaskResourceRequirement> personReqs = task.getResourceRequirementList().stream()
                    .filter(req -> req != null && ResourceConstants.RESOURCE_TYPE_PERSON.equals(req.getResourceType()))
                    .filter(this::isMandatoryRequirement)
                    .toList();
            if (!personReqs.isEmpty()) {
                personId = choosePerson(personReqs, task, schedulingContext, runtimeContext);
                if (personId == null) {
                    log.warn("任务 {} 没有可用人员", task.getTaskId());
                    return null;
                }
                personSequence = runtimeContext.getNextSequence(ResourceConstants.RESOURCE_TYPE_PERSON, personId);
            }
        }

        LocalDateTime personNextTime = personId != null
                ? runtimeContext.getNextAvailableTime(ResourceConstants.RESOURCE_TYPE_PERSON, personId)
                : null;
        LocalDateTime effectiveStart = maxTime(
                workstationChoice.plannedStart, personNextTime, effectiveEarliestStart, assignmentStart);

        Calendar calendar = calendarMap.get(workstationChoice.workstationResource.getCalendarId());
        LocalDateTime plannedStart = adjustToShiftStart(calendar, effectiveStart);
        if (plannedStart == null) {
            return null;
        }
        long duration = task.getStdDurationMin() == null ? 0L : task.getStdDurationMin();
        TimeWindow window = adjustForShiftEnd(calendar, plannedStart, duration);
        if (window == null) {
            return null;
        }

        return new ResourceChoice(
                null, window.start, window.end,
                workstationChoice.sequenceOnResource, 0, null, null,
                null, null, personId, personSequence,
                workstationChoice.workstationId, workstationChoice.sequenceOnResource);
    }

    private boolean requiresMachine(OperationTask task) {
        if (task == null || CollectionUtils.isEmpty(task.getResourceRequirementList())) {
            return true;
        }
        return task.getResourceRequirementList().stream()
                .filter(Objects::nonNull)
                .filter(this::isMandatoryRequirement)
                .anyMatch(req -> ResourceConstants.RESOURCE_TYPE_MACHINE.equals(req.getResourceType()));
    }

    private boolean requiresWorkstation(OperationTask task) {
        if (task == null || CollectionUtils.isEmpty(task.getResourceRequirementList())) {
            return false;
        }
        return task.getResourceRequirementList().stream()
                .filter(Objects::nonNull)
                .filter(this::isMandatoryRequirement)
                .anyMatch(req -> ResourceConstants.RESOURCE_TYPE_WORKSTATION.equals(req.getResourceType()));
    }

    // ========================== 需求解析（回写选中资源ID） ==========================

    /**
     * 将级联选择的结果回写到需求列表中。
     *
     * <p>
     * 对于需求中未指定 resourceId 的 MOLD/PERSON/MACHINE 类型需求，
     * 用 calculator 选中资源的 ID 填充，以便持久化服务正确生成 TaskAssignmentResource。
     * </p>
     *
     * @param requirements 原始需求列表
     * @param machineId    选中的机台ID
     * @param moldId       选中的模具ID（可为 null）
     * @param personId     选中的人员ID（可为 null）
     * @return 填充后的需求列表副本
     */
    private List<TaskResourceRequirement> resolveSelectedResources(
            List<TaskResourceRequirement> requirements,
            Long machineId, Long moldId, Long personId, Long workstationId, Integer changeoverTimeMin) {
        if (CollectionUtils.isEmpty(requirements)) {
            return List.of();
        }
        List<TaskResourceRequirement> resolved = new ArrayList<>(requirements.size());
        for (TaskResourceRequirement req : requirements) {
            if (req == null) {
                continue;
            }
            TaskResourceRequirement copy = new TaskResourceRequirement();
            copy.setRequirementId(req.getRequirementId());
            copy.setTaskId(req.getTaskId());
            copy.setResourceType(req.getResourceType());
            copy.setResourceRole(req.getResourceRole());
            copy.setResourceId(req.getResourceId());
            copy.setCapabilityCode(req.getCapabilityCode());
            copy.setRequiredCount(req.getRequiredCount());
            copy.setIsMandatory(req.getIsMandatory());
            copy.setChangeoverSourceResourceId(req.getChangeoverSourceResourceId());
            copy.setChangeoverTimeMin(changeoverTimeMin != null ? changeoverTimeMin : req.getChangeoverTimeMin());

            if (copy.getResourceId() == null) {
                if (ResourceConstants.RESOURCE_TYPE_MACHINE.equals(copy.getResourceType())
                        && machineId != null) {
                    copy.setResourceId(machineId);
                } else if (ResourceConstants.RESOURCE_TYPE_MOLD.equals(copy.getResourceType())
                        && moldId != null) {
                    copy.setResourceId(moldId);
                } else if (ResourceConstants.RESOURCE_TYPE_PERSON.equals(copy.getResourceType())
                        && personId != null) {
                    copy.setResourceId(personId);
                } else if (ResourceConstants.RESOURCE_TYPE_WORKSTATION.equals(copy.getResourceType())
                        && workstationId != null) {
                    copy.setResourceId(workstationId);
                }
            }
            resolved.add(copy);
        }
        return resolved;
    }

    // ========================== 机台选择（复用原有逻辑） ==========================

    /**
     * 为单个任务选择最优机台。
     *
     * <p>
     * 选择策略：贪心算法（选择最早能开始任务的机台）。
     * </p>
     */
    private MachineChoice chooseBestMachine(OperationTask task,
            List<Resource> machines,
            Map<Long, Calendar> calendarMap,
            ResourceRuntimeContext runtimeContext,
            LocalDateTime assignmentStart,
            SchedulingStrategy strategy,
            LocalDateTime effectiveEarliestStart,
            TaskSchedulingQueryService.SchedulingResourceContext schedulingContext) {
        MachineChoice best = null;
        Comparator<MachineChoice> comparator = machineChoiceComparator(strategy, task);

        Long targetMoldId = resolvePlannedMoldId(task);
        boolean sameMoldFirst = task != null
                && RouteOperationConstants.QUEUE_SAME_MOLD_FIRST.equals(task.getQueuePolicy());

        for (Resource machine : machines) {
            if (machine == null) {
                continue;
            }
            if (!machineSatisfiesTaskRequirements(task, machine)) {
                continue;
            }

            Calendar calendar = calendarMap.get(machine.getCalendarId());
            LocalDateTime machineNextTime = runtimeContext.getNextAvailableTime(
                    ResourceConstants.RESOURCE_TYPE_MACHINE, machine.getResourceId());

            Integer changeoverTimeMin = null;
            Long changeoverSourceTaskId = null;
            LocalDateTime afterChangeover = machineNextTime;
            if (requiresChangeover(task) && schedulingContext != null) {
                ChangeoverCalculator.MachineAssignmentSnapshot previous = runtimeContext
                        .getMachineLastAssignment(machine.getResourceId());
                ChangeoverCalculator.MachineAssignmentSnapshot current = buildCurrentChangeoverSnapshot(
                        task, schedulingContext);
                if (previous != null && schedulingContext.getChangeoverRule() != null) {
                    changeoverTimeMin = changeoverCalculator.calculateChangeoverMin(
                            schedulingContext.getChangeoverRule(), previous, current);
                    changeoverSourceTaskId = previous.getSourceTaskId();
                    if (changeoverTimeMin != null && changeoverTimeMin > 0) {
                        LocalDateTime base = machineNextTime == null ? assignmentStart : machineNextTime;
                        afterChangeover = base.plusMinutes(changeoverTimeMin);
                    }
                }
            }

            LocalDateTime candidate = maxTime(effectiveEarliestStart, afterChangeover, assignmentStart);
            LocalDateTime plannedStart = adjustToShiftStart(calendar, candidate);
            if (plannedStart == null) {
                continue;
            }

            long duration = task.getStdDurationMin() == null ? 0L : task.getStdDurationMin();
            TimeWindow window = adjustForShiftEnd(calendar, plannedStart, duration);
            if (window == null) {
                continue;
            }

            Long sequenceOnResource = runtimeContext.getNextSequence(
                    ResourceConstants.RESOURCE_TYPE_MACHINE, machine.getResourceId());

            boolean sameMoldPreferred = false;
            if (sameMoldFirst && targetMoldId != null) {
                ChangeoverCalculator.MachineAssignmentSnapshot previousAssignment = runtimeContext
                        .getMachineLastAssignment(machine.getResourceId());
                sameMoldPreferred = previousAssignment != null
                        && Objects.equals(targetMoldId, previousAssignment.getMoldId());
            }

            MachineChoice choice = new MachineChoice(
                    machine.getResourceId(),
                    machine,
                    window.start,
                    window.end,
                    sequenceOnResource,
                    estimateSetupCost(task, machine, changeoverTimeMin),
                    changeoverTimeMin,
                    changeoverSourceTaskId,
                    sameMoldPreferred);

            if (best == null || comparator.compare(choice, best) < 0) {
                best = choice;
            }
        }
        return best;
    }

    private ChangeoverCalculator.MachineAssignmentSnapshot buildCurrentChangeoverSnapshot(
            OperationTask task,
            TaskSchedulingQueryService.SchedulingResourceContext schedulingContext) {
        Product product = task == null || task.getProductId() == null || schedulingContext == null
                ? null
                : schedulingContext.getProductMap().get(task.getProductId());
        return new ChangeoverCalculator.MachineAssignmentSnapshot(
                resolvePlannedMoldId(task),
                task == null ? null : task.getProductId(),
                product == null ? null : product.getMaterialCode(),
                product == null ? null : product.getColorCode(),
                task == null ? null : task.getTaskId());
    }

    private Long resolvePlannedMoldId(OperationTask task) {
        if (task == null || CollectionUtils.isEmpty(task.getResourceRequirementList())) {
            return null;
        }
        return task.getResourceRequirementList().stream()
                .filter(req -> req != null && ResourceConstants.RESOURCE_TYPE_MOLD.equals(req.getResourceType()))
                .map(TaskResourceRequirement::getResourceId)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private boolean requiresChangeover(OperationTask task) {
        if (task == null) {
            return false;
        }
        if (routeRuleRegistry != null && StringUtils.isNotEmpty(task.getEligibleResourceRule())) {
            return routeRuleRegistry.findRule(task.getEligibleResourceRule())
                    .map(RouteEligibleResourceRule::triggersChangeover)
                    .orElse(false);
        }
        if ("SETUP".equals(task.getOpCode())) {
            return true;
        }
        return inferSetupClassFromRequirements(task);
    }

    private boolean inferSetupClassFromRequirements(OperationTask task) {
        if (task == null || CollectionUtils.isEmpty(task.getResourceRequirementList())) {
            return false;
        }
        boolean requiresMachine = false;
        boolean requiresMold = false;
        for (TaskResourceRequirement requirement : task.getResourceRequirementList()) {
            if (requirement == null || !isMandatoryRequirement(requirement)) {
                continue;
            }
            if (ResourceConstants.RESOURCE_TYPE_MACHINE.equals(requirement.getResourceType())) {
                requiresMachine = true;
            }
            if (ResourceConstants.RESOURCE_TYPE_MOLD.equals(requirement.getResourceType())) {
                requiresMold = true;
            }
        }
        return requiresMachine && !requiresMold;
    }

    private WorkstationChoice chooseBestWorkstation(OperationTask task,
            List<Resource> workstations,
            Map<Long, Calendar> calendarMap,
            ResourceRuntimeContext runtimeContext,
            LocalDateTime assignmentStart,
            LocalDateTime effectiveEarliestStart) {
        WorkstationChoice best = null;
        for (Resource workstation : workstations) {
            if (workstation == null) {
                continue;
            }
            if (!resourceSupportsTaskCapability(workstation, task)) {
                continue;
            }
            Calendar calendar = calendarMap.get(workstation.getCalendarId());
            LocalDateTime workstationNextTime = runtimeContext.getNextAvailableTime(
                    ResourceConstants.RESOURCE_TYPE_WORKSTATION, workstation.getResourceId());
            LocalDateTime candidate = maxTime(effectiveEarliestStart, workstationNextTime, assignmentStart);
            LocalDateTime plannedStart = adjustToShiftStart(calendar, candidate);
            if (plannedStart == null) {
                continue;
            }
            long duration = task.getStdDurationMin() == null ? 0L : task.getStdDurationMin();
            TimeWindow window = adjustForShiftEnd(calendar, plannedStart, duration);
            if (window == null) {
                continue;
            }
            Long sequenceOnResource = runtimeContext.getNextSequence(
                    ResourceConstants.RESOURCE_TYPE_WORKSTATION, workstation.getResourceId());
            WorkstationChoice choice = new WorkstationChoice(
                    workstation.getResourceId(), workstation, window.start, window.end, sequenceOnResource);
            if (best == null || choice.plannedStart.isBefore(best.plannedStart)) {
                best = choice;
            }
        }
        return best;
    }

    /**
     * 检查资源是否满足任务需求（含人员技能检查）。
     */
    private boolean machineSatisfiesTaskRequirements(OperationTask task, Resource machine) {
        if (task == null || CollectionUtils.isEmpty(task.getResourceRequirementList())) {
            return true;
        }
        if (!resourceSupportsTaskCapability(machine, task)) {
            return false;
        }
        for (TaskResourceRequirement requirement : task.getResourceRequirementList()) {
            if (requirement == null || !isMandatoryRequirement(requirement)) {
                continue;
            }
            if (ResourceConstants.RESOURCE_TYPE_MACHINE.equals(requirement.getResourceType())
                    && requirement.getResourceId() != null
                    && !Objects.equals(requirement.getResourceId(), machine.getResourceId())) {
                return false;
            }
            if (ResourceConstants.RESOURCE_TYPE_MOLD.equals(requirement.getResourceType())
                    && requirement.getResourceId() != null
                    && !machineSupportsMold(machine, requirement.getResourceId())) {
                return false;
            }
        }
        return true;
    }

    private boolean isMandatoryRequirement(TaskResourceRequirement requirement) {
        return requirement.getIsMandatory() == null || requirement.getIsMandatory() != 0;
    }

    private boolean machineSupportsMold(Resource machineResource, Long moldId) {
        Machine machine = machineResource.getMachine();
        if (machine == null || CollectionUtils.isEmpty(machine.getMoldCompatibilityList())) {
            return false;
        }
        return machine.getMoldCompatibilityList().stream()
                .filter(Objects::nonNull)
                .anyMatch(item -> Objects.equals(item.getMoldId(), moldId)
                        && (item.getIsCompatible() == null || item.getIsCompatible() != 0));
    }

    private boolean resourceSupportsTaskCapability(Resource resource, OperationTask task) {
        if (resource == null || task == null || StringUtils.isEmpty(task.getOpCode())) {
            return true;
        }
        if (task.getProductId() == null || CollectionUtils.isEmpty(resource.getCapabilityList())) {
            return true;
        }
        return resource.getCapabilityList().stream()
                .filter(Objects::nonNull)
                .filter(cap -> Integer.valueOf(1).equals(cap.getIsEnabled()))
                .anyMatch(cap -> StringUtils.equals(cap.getOpCode(), task.getOpCode())
                        && Objects.equals(cap.getProductId(), task.getProductId()));
    }

    private Integer estimateSetupCost(OperationTask task, Resource machine, Integer changeoverTimeMin) {
        if (changeoverTimeMin != null) {
            return changeoverTimeMin;
        }
        if (task != null && CollectionUtils.isNotEmpty(task.getResourceRequirementList())) {
            Integer requirementCost = task.getResourceRequirementList().stream()
                    .filter(Objects::nonNull)
                    .filter(this::isMandatoryRequirement)
                    .map(TaskResourceRequirement::getChangeoverTimeMin)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);
            if (requirementCost != null) {
                return requirementCost;
            }
        }
        Machine machineDetail = machine == null ? null : machine.getMachine();
        return machineDetail == null || machineDetail.getDefaultSetupTimeMin() == null ? 0
                : machineDetail.getDefaultSetupTimeMin();
    }

    // ========================== 模具选择 ==========================

    /**
     * 选择可用模具。
     *
     * <p>
     * 如果需求指定了 resourceId，则仅检查该模具是否在机台兼容列表中且可用；
     * 否则从机台兼容模具列表中选择最早可用的。
     * </p>
     *
     * @param moldReqs          模具需求列表（mandatory）
     * @param machineResource   已选定的机台资源
     * @param runtimeContext    资源运行时上下文
     * @param schedulingContext 排程资源上下文（获取模具资源列表）
     * @return 选中的模具ID，无可用时返回 null
     */
    private Long chooseMold(List<TaskResourceRequirement> moldReqs,
            Resource machineResource,
            ResourceRuntimeContext runtimeContext,
            TaskSchedulingQueryService.SchedulingResourceContext schedulingContext) {
        Machine machine = machineResource == null ? null : machineResource.getMachine();
        List<Resource> moldResources = schedulingContext.getResourcesByType()
                .getOrDefault(ResourceConstants.RESOURCE_TYPE_MOLD, List.of());

        for (TaskResourceRequirement req : moldReqs) {
            if (req.getResourceId() != null) {
                // 指定了模具ID：检查兼容性和可用性
                if (!machineSupportsMold(machineResource, req.getResourceId())) {
                    return null;
                }
                LocalDateTime moldNext = runtimeContext.getNextAvailableTime(
                        ResourceConstants.RESOURCE_TYPE_MOLD, req.getResourceId());
                if (moldNext == null) {
                    return req.getResourceId();
                }
                // 如果模具可用时间已经满足（模具空闲），直接选
                return req.getResourceId();
            }

            // 未指定模具ID：从机台兼容模具中选最早可用的
            if (machine == null || CollectionUtils.isEmpty(machine.getMoldCompatibilityList())) {
                continue;
            }
            Long bestMoldId = null;
            LocalDateTime bestMoldNext = null;
            for (MachineMoldCompatibility compat : machine.getMoldCompatibilityList()) {
                if (compat == null || compat.getMoldId() == null
                        || (compat.getIsCompatible() != null && compat.getIsCompatible() == 0)) {
                    continue;
                }
                Long moldId = compat.getMoldId();
                // 确认模具在可用资源列表中
                boolean isAvailable = moldResources.stream()
                        .anyMatch(r -> r != null && Objects.equals(r.getResourceId(), moldId));
                if (!isAvailable) {
                    continue;
                }
                LocalDateTime moldNext = runtimeContext.getNextAvailableTime(
                        ResourceConstants.RESOURCE_TYPE_MOLD, moldId);
                if (bestMoldNext == null || (moldNext == null) || moldNext.isBefore(bestMoldNext)) {
                    bestMoldId = moldId;
                    bestMoldNext = moldNext;
                }
            }
            if (bestMoldId != null) {
                return bestMoldId;
            }
        }
        return null;
    }

    // ========================== 人员选择 ==========================

    /**
     * 通过 opCode 匹配选择最早可用的人员。
     *
     * <p>
     * 匹配规则：
     * 1. 如果需求指定了 resourceId，则检查该人员是否存在且技能匹配
     * 2. 否则从所有可用人员中通过 ResourceCapability.opCode 匹配 capabilityCode，
     * 选择 isEnabled=1 且最早可用的人员
     * </p>
     *
     * @param personReqs        人员需求列表（mandatory）
     * @param schedulingContext 排程资源上下文（获取人员资源列表）
     * @param runtimeContext    资源运行时上下文
     * @return 选中的人员ID，无可用时返回 null
     */
    private Long choosePerson(List<TaskResourceRequirement> personReqs,
            OperationTask task,
            TaskSchedulingQueryService.SchedulingResourceContext schedulingContext,
            ResourceRuntimeContext runtimeContext) {
        List<Resource> personResources = schedulingContext.getResourcesByType()
                .getOrDefault(ResourceConstants.RESOURCE_TYPE_PERSON, List.of());

        for (TaskResourceRequirement req : personReqs) {
            if (req.getResourceId() != null) {
                if (personHasCapability(req.getResourceId(), req.getCapabilityCode(), task, personResources)) {
                    return req.getResourceId();
                }
                return null;
            }

            String capabilityCode = req.getCapabilityCode();
            if (StringUtils.isEmpty(capabilityCode)) {
                continue;
            }
            Long bestPersonId = null;
            LocalDateTime bestPersonNext = null;
            for (Resource person : personResources) {
                if (person == null) {
                    continue;
                }
                if (!personHasCapability(person.getResourceId(), capabilityCode, task, personResources)) {
                    continue;
                }
                LocalDateTime personNext = runtimeContext.getNextAvailableTime(
                        ResourceConstants.RESOURCE_TYPE_PERSON, person.getResourceId());
                if (bestPersonNext == null || (personNext == null) || personNext.isBefore(bestPersonNext)) {
                    bestPersonId = person.getResourceId();
                    bestPersonNext = personNext;
                }
            }
            if (bestPersonId != null) {
                return bestPersonId;
            }
        }
        return null;
    }

    private boolean personHasCapability(Long personId,
            String capabilityCode,
            OperationTask task,
            List<Resource> personResources) {
        if (personId == null || StringUtils.isEmpty(capabilityCode)) {
            return false;
        }
        for (Resource person : personResources) {
            if (person == null || !Objects.equals(person.getResourceId(), personId)) {
                continue;
            }
            if (CollectionUtils.isEmpty(person.getCapabilityList())) {
                return task == null || task.getProductId() == null;
            }
            return person.getCapabilityList().stream()
                    .filter(Objects::nonNull)
                    .filter(cap -> Integer.valueOf(1).equals(cap.getIsEnabled()))
                    .anyMatch(cap -> {
                        if (!StringUtils.equals(cap.getOpCode(), capabilityCode)) {
                            return false;
                        }
                        if (task == null || task.getProductId() == null) {
                            return true;
                        }
                        return Objects.equals(cap.getProductId(), task.getProductId());
                    });
        }
        return false;
    }

    // ========================== 比较器 ==========================

    /**
     * 根据策略构建机台选择比较器。
     */
    private Comparator<MachineChoice> machineChoiceComparator(SchedulingStrategy strategy, OperationTask task) {
        Comparator<MachineChoice> base;
        if (strategy == SchedulingStrategy.LOWEST_COST) {
            base = Comparator
                    .comparing((MachineChoice item) -> item.setupCostMin,
                            Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(item -> item.plannedEnd, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(item -> item.plannedStart, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(item -> item.machineId, Comparator.nullsLast(Comparator.naturalOrder()));
        } else if (strategy == SchedulingStrategy.EARLIEST_FINISH) {
            base = Comparator
                    .comparing((MachineChoice item) -> item.plannedEnd, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(item -> item.plannedStart, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(item -> item.machineId, Comparator.nullsLast(Comparator.naturalOrder()));
        } else {
            base = Comparator
                    .comparing((MachineChoice item) -> item.plannedStart, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(item -> item.plannedEnd, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(item -> item.machineId, Comparator.nullsLast(Comparator.naturalOrder()));
        }
        if (task != null && RouteOperationConstants.QUEUE_SAME_MOLD_FIRST.equals(task.getQueuePolicy())) {
            return Comparator
                    .comparing((MachineChoice item) -> item.sameMoldPreferred ? 0 : 1)
                    .thenComparing(base);
        }
        return base;
    }

    /**
     * 根据策略构建任务排序比较器。
     */
    private Comparator<OperationTask> taskComparator(SchedulingStrategy strategy,
            Map<Long, TaskSchedulingPriorityDTO> priorityMap) {
        if (strategy == SchedulingStrategy.DUE_DATE_PRIORITY) {
            return Comparator
                    .comparing((OperationTask task) -> priorityDate(task, priorityMap),
                            Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(task -> priorityValue(task, priorityMap),
                            Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(task -> task.getEarliestStart(), Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(task -> task.getSequence(), Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(OperationTask::getTaskId, Comparator.nullsLast(Comparator.naturalOrder()));
        }
        if (strategy == SchedulingStrategy.LOWEST_COST) {
            return Comparator
                    .comparing((OperationTask task) -> task.getChangeoverTimeMin(),
                            Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(task -> task.getEarliestStart(), Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(task -> task.getSequence(), Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(OperationTask::getTaskId, Comparator.nullsLast(Comparator.naturalOrder()));
        }
        if (strategy == SchedulingStrategy.EARLIEST_FINISH) {
            return Comparator
                    .comparing((OperationTask task) -> estimateFinish(task),
                            Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(task -> task.getEarliestStart(), Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(task -> task.getSequence(), Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(OperationTask::getTaskId, Comparator.nullsLast(Comparator.naturalOrder()));
        }
        return Comparator
                .comparing((OperationTask task) -> task.getEarliestStart(),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(task -> task.getSequence(), Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(OperationTask::getTaskId, Comparator.nullsLast(Comparator.naturalOrder()));
    }

    /** 预估任务结束时间 = 最早开始时间 + 标准时长（不考虑日历班次，仅用于排序比较） */
    private LocalDateTime estimateFinish(OperationTask task) {
        if (task == null) {
            return null;
        }
        LocalDateTime start = task.getEarliestStart();
        if (start == null) {
            return null;
        }
        long duration = task.getStdDurationMin() == null ? 0L : task.getStdDurationMin();
        return start.plusMinutes(duration);
    }

    private LocalDateTime priorityDate(OperationTask task, Map<Long, TaskSchedulingPriorityDTO> priorityMap) {
        TaskSchedulingPriorityDTO context = priorityMap == null || task == null ? null
                : priorityMap.get(task.getTaskId());
        return context == null ? null : context.getDueDate();
    }

    private Long priorityValue(OperationTask task, Map<Long, TaskSchedulingPriorityDTO> priorityMap) {
        TaskSchedulingPriorityDTO context = priorityMap == null || task == null ? null
                : priorityMap.get(task.getTaskId());
        return context == null ? null : context.getPriority();
    }

    // ========================== 时间工具 ==========================

    private LocalDateTime maxTime(LocalDateTime... times) {
        LocalDateTime max = null;
        if (times == null || times.length == 0) {
            return null;
        }
        for (LocalDateTime time : times) {
            if (time == null) {
                continue;
            }
            if (max == null || time.isAfter(max)) {
                max = time;
            }
        }
        return max;
    }

    private LocalDateTime adjustToShiftStart(Calendar calendar, LocalDateTime time) {
        if (time == null || calendar == null) {
            return time;
        }
        String shiftStart = calendar.getShiftStart();
        String shiftEnd = calendar.getShiftEnd();
        if (StringUtils.isEmpty(shiftStart) || StringUtils.isEmpty(shiftEnd)) {
            return time;
        }
        LocalTime startTime;
        LocalTime endTime;
        try {
            startTime = LocalTime.parse(shiftStart);
            endTime = LocalTime.parse(shiftEnd);
        } catch (Exception ex) {
            return time;
        }
        LocalDate date = time.toLocalDate();
        date = nextWorkday(calendar, date);

        LocalDateTime shiftStartTime = LocalDateTime.of(date, startTime);
        LocalDateTime shiftEndTime = LocalDateTime.of(date, endTime);

        if (time.isBefore(shiftStartTime)) {
            return shiftStartTime;
        }
        if (!time.isBefore(shiftEndTime)) {
            LocalDate nextDate = nextWorkday(calendar, date.plusDays(1));
            return LocalDateTime.of(nextDate, startTime);
        }
        return time;
    }

    private TimeWindow adjustForShiftEnd(Calendar calendar, LocalDateTime start, long durationMinutes) {
        if (start == null) {
            return null;
        }
        LocalDateTime end = start.plusMinutes(durationMinutes);
        if (calendar == null) {
            return new TimeWindow(start, end);
        }
        String shiftStart = calendar.getShiftStart();
        String shiftEnd = calendar.getShiftEnd();
        if (StringUtils.isEmpty(shiftStart) || StringUtils.isEmpty(shiftEnd)) {
            return new TimeWindow(start, end);
        }
        LocalTime endTime;
        LocalTime startTime;
        try {
            startTime = LocalTime.parse(shiftStart);
            endTime = LocalTime.parse(shiftEnd);
        } catch (Exception ex) {
            return new TimeWindow(start, end);
        }
        LocalDate date = start.toLocalDate();
        LocalDateTime shiftEndTime = LocalDateTime.of(date, endTime);
        if (end.isAfter(shiftEndTime)) {
            LocalDate nextDate = nextWorkday(calendar, date.plusDays(1));
            LocalDateTime nextStart = LocalDateTime.of(nextDate, startTime);
            LocalDateTime nextEnd = nextStart.plusMinutes(durationMinutes);
            return new TimeWindow(nextStart, nextEnd);
        }
        return new TimeWindow(start, end);
    }

    private LocalDate nextWorkday(Calendar calendar, LocalDate date) {
        if (calendar == null || date == null) {
            return date;
        }
        String workdayPattern = calendar.getWorkdayPattern();
        LocalDate next = date;
        int guard = 0;
        while (!isWorkday(workdayPattern, next.getDayOfWeek()) && guard < 7) {
            next = next.plusDays(1);
            guard++;
        }
        return next;
    }

    private boolean isWorkday(String workdayPattern, DayOfWeek dayOfWeek) {
        if (StringUtils.isEmpty(workdayPattern) || dayOfWeek == null) {
            return true;
        }
        String normalized = workdayPattern.replace(" ", "");
        if (normalized.contains(",")) {
            String[] tokens = normalized.split(",");
            for (String token : tokens) {
                if (matchesDayToken(token, dayOfWeek)) {
                    return true;
                }
            }
            return false;
        }

        if (normalized.contains("-")) {
            String[] range = normalized.split("-");
            if (range.length != 2) {
                return false;
            }
            DayOfWeek start = parseDayOfWeek(range[0]);
            DayOfWeek end = parseDayOfWeek(range[1]);
            if (start == null || end == null) {
                return false;
            }
            int startValue = start.getValue();
            int endValue = end.getValue();
            int dayValue = dayOfWeek.getValue();
            if (startValue <= endValue) {
                return dayValue >= startValue && dayValue <= endValue;
            }
            return dayValue >= startValue || dayValue <= endValue;
        }

        return matchesDayToken(normalized, dayOfWeek);
    }

    private boolean matchesDayToken(String token, DayOfWeek dayOfWeek) {
        DayOfWeek parsed = parseDayOfWeek(token);
        return parsed != null && parsed.equals(dayOfWeek);
    }

    private DayOfWeek parseDayOfWeek(String token) {
        if (StringUtils.isEmpty(token)) {
            return null;
        }
        String key = token.trim().toLowerCase();
        switch (key) {
            case "mon":
            case "monday":
            case "1":
                return DayOfWeek.MONDAY;
            case "tue":
            case "tues":
            case "tuesday":
            case "2":
                return DayOfWeek.TUESDAY;
            case "wed":
            case "wednesday":
            case "3":
                return DayOfWeek.WEDNESDAY;
            case "thu":
            case "thur":
            case "thurs":
            case "thursday":
            case "4":
                return DayOfWeek.THURSDAY;
            case "fri":
            case "friday":
            case "5":
                return DayOfWeek.FRIDAY;
            case "sat":
            case "saturday":
            case "6":
                return DayOfWeek.SATURDAY;
            case "sun":
            case "sunday":
            case "7":
                return DayOfWeek.SUNDAY;
            default:
                return null;
        }
    }

    // ========================== 内部数据类 ==========================

    /**
     * 多资源运行时上下文（内存快照）。
     *
     * <p>
     * 按 (resourceType, resourceId) 维度维护各资源的最早可用时间和下一个序号。
     * 在排程计算过程中不断更新，避免频繁查询数据库。
     * </p>
     */
    public static class ResourceRuntimeContext {
        /** resourceType -> (resourceId -> 最早可用时间) */
        private final Map<String, Map<Long, LocalDateTime>> nextAvailableTimeMap = new HashMap<>();
        /** resourceType -> (resourceId -> 下一个序号) */
        private final Map<String, Map<Long, Long>> nextSequenceMap = new HashMap<>();
        /** machineId -> 最近一次派工快照 */
        private final Map<Long, ChangeoverCalculator.MachineAssignmentSnapshot> machineLastAssignmentMap = new HashMap<>();

        /**
         * 获取资源的最早可用时间。
         *
         * @param resourceType 资源类型
         * @param resourceId   资源ID
         * @return 最早可用时间（null 表示该资源从未被占用）
         */
        public LocalDateTime getNextAvailableTime(String resourceType, Long resourceId) {
            Map<Long, LocalDateTime> typeMap = nextAvailableTimeMap.get(resourceType);
            return typeMap == null ? null : typeMap.get(resourceId);
        }

        /**
         * 获取资源的下一个序号。
         *
         * @param resourceType 资源类型
         * @param resourceId   资源ID
         * @return 下一个序号（不存在则返回 1）
         */
        public Long getNextSequence(String resourceType, Long resourceId) {
            Map<Long, Long> typeMap = nextSequenceMap.get(resourceType);
            return typeMap == null ? 1L : typeMap.getOrDefault(resourceId, 1L);
        }

        /**
         * 更新资源运行状态。
         *
         * @param resourceType 资源类型
         * @param resourceId   资源ID
         * @param plannedEnd   计划结束时间
         * @param usedSequence 本次使用的序号
         */
        public void update(String resourceType, Long resourceId, LocalDateTime plannedEnd, Long usedSequence) {
            if (StringUtils.isEmpty(resourceType) || resourceId == null) {
                return;
            }
            if (plannedEnd != null) {
                nextAvailableTimeMap
                        .computeIfAbsent(resourceType, k -> new HashMap<>())
                        .put(resourceId, plannedEnd);
            }
            if (usedSequence != null) {
                nextSequenceMap
                        .computeIfAbsent(resourceType, k -> new HashMap<>())
                        .put(resourceId, usedSequence + 1L);
            }
        }

        /**
         * 设置资源的最早可用时间（预加载时使用）。
         */
        void setNextAvailableTime(String resourceType, Long resourceId, LocalDateTime time) {
            nextAvailableTimeMap
                    .computeIfAbsent(resourceType, k -> new HashMap<>())
                    .put(resourceId, time);
        }

        /**
         * 设置资源的下一个序号（预加载时使用）。
         */
        void setNextSequence(String resourceType, Long resourceId, Long sequence) {
            nextSequenceMap
                    .computeIfAbsent(resourceType, k -> new HashMap<>())
                    .put(resourceId, sequence);
        }

        public ChangeoverCalculator.MachineAssignmentSnapshot getMachineLastAssignment(Long machineId) {
            return machineLastAssignmentMap.get(machineId);
        }

        public void setMachineLastAssignment(Long machineId,
                ChangeoverCalculator.MachineAssignmentSnapshot snapshot) {
            if (machineId != null && snapshot != null) {
                machineLastAssignmentMap.put(machineId, snapshot);
            }
        }
    }

    /**
     * 资源选择结果（扩展 MachineChoice，包含模具和人员信息）。
     */
    public static class ResourceChoice {
        private final Long machineId;
        private final LocalDateTime plannedStart;
        private final LocalDateTime plannedEnd;
        private final Long sequenceOnResource;
        private final Integer setupCostMin;
        private final Integer changeoverTimeMin;
        private final Long changeoverSourceTaskId;
        private final Long moldId;
        private final Long moldSequence;
        private final Long personId;
        private final Long personSequence;
        private final Long workstationId;
        private final Long workstationSequence;

        public ResourceChoice(Long machineId,
                LocalDateTime plannedStart,
                LocalDateTime plannedEnd,
                Long sequenceOnResource,
                Integer setupCostMin,
                Integer changeoverTimeMin,
                Long changeoverSourceTaskId,
                Long moldId,
                Long moldSequence,
                Long personId,
                Long personSequence,
                Long workstationId,
                Long workstationSequence) {
            this.machineId = machineId;
            this.plannedStart = plannedStart;
            this.plannedEnd = plannedEnd;
            this.sequenceOnResource = sequenceOnResource;
            this.setupCostMin = setupCostMin;
            this.changeoverTimeMin = changeoverTimeMin;
            this.changeoverSourceTaskId = changeoverSourceTaskId;
            this.moldId = moldId;
            this.moldSequence = moldSequence;
            this.personId = personId;
            this.personSequence = personSequence;
            this.workstationId = workstationId;
            this.workstationSequence = workstationSequence;
        }
    }

    /**
     * 机台选择结果（内部使用，包含 Resource 引用以便级联选择时获取兼容模具列表）。
     */
    static class MachineChoice {
        private final Long machineId;
        private final Resource machineResource;
        private final LocalDateTime plannedStart;
        private final LocalDateTime plannedEnd;
        private final Long sequenceOnResource;
        private final Integer setupCostMin;
        private final Integer changeoverTimeMin;
        private final Long changeoverSourceTaskId;
        private final boolean sameMoldPreferred;

        MachineChoice(Long machineId,
                Resource machineResource,
                LocalDateTime plannedStart,
                LocalDateTime plannedEnd,
                Long sequenceOnResource,
                Integer setupCostMin,
                Integer changeoverTimeMin,
                Long changeoverSourceTaskId,
                boolean sameMoldPreferred) {
            this.machineId = machineId;
            this.machineResource = machineResource;
            this.plannedStart = plannedStart;
            this.plannedEnd = plannedEnd;
            this.sequenceOnResource = sequenceOnResource;
            this.setupCostMin = setupCostMin;
            this.changeoverTimeMin = changeoverTimeMin;
            this.changeoverSourceTaskId = changeoverSourceTaskId;
            this.sameMoldPreferred = sameMoldPreferred;
        }
    }

    static class WorkstationChoice {
        private final Long workstationId;
        private final Resource workstationResource;
        private final LocalDateTime plannedStart;
        private final LocalDateTime plannedEnd;
        private final Long sequenceOnResource;

        WorkstationChoice(Long workstationId,
                Resource workstationResource,
                LocalDateTime plannedStart,
                LocalDateTime plannedEnd,
                Long sequenceOnResource) {
            this.workstationId = workstationId;
            this.workstationResource = workstationResource;
            this.plannedStart = plannedStart;
            this.plannedEnd = plannedEnd;
            this.sequenceOnResource = sequenceOnResource;
        }
    }

    /**
     * 时间窗口。
     */
    public static class TimeWindow {
        private final LocalDateTime start;
        private final LocalDateTime end;

        public TimeWindow(LocalDateTime start, LocalDateTime end) {
            this.start = start;
            this.end = end;
        }
    }

    /**
     * 批次排程结果。
     */
    public static class ScheduleBatchResult {
        private final List<TaskAssignment> assignments;
        private final List<Long> taskIds;

        public ScheduleBatchResult(List<TaskAssignment> assignments, List<Long> taskIds) {
            this.assignments = assignments;
            this.taskIds = taskIds;
        }

        public List<TaskAssignment> getAssignments() {
            return assignments;
        }

        public List<Long> getTaskIds() {
            return taskIds;
        }
    }

    /**
     * 机台运行时上下文（内存快照）。
     *
     * @deprecated 使用 {@link ResourceRuntimeContext} 替代，支持多资源类型。
     */
    @Deprecated
    public static class MachineRuntimeContext {
        private final Map<Long, LocalDateTime> nextAvailableTimeMap = new HashMap<>();
        private final Map<Long, Long> nextSequenceMap = new HashMap<>();

        public LocalDateTime getNextAvailableTime(Long machineId) {
            return nextAvailableTimeMap.get(machineId);
        }

        public Long getNextSequence(Long machineId) {
            return nextSequenceMap.getOrDefault(machineId, 1L);
        }

        public void update(Long machineId, LocalDateTime plannedEnd, Long usedSequence) {
            if (machineId != null && plannedEnd != null) {
                nextAvailableTimeMap.put(machineId, plannedEnd);
            }
            if (machineId != null && usedSequence != null) {
                nextSequenceMap.put(machineId, usedSequence + 1L);
            }
        }
    }
}
