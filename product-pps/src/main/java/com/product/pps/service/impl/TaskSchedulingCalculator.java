package com.product.pps.service.impl;

import com.product.common.constant.ResourceConstants;
import com.product.common.exception.ServiceException;
import com.product.common.utils.StringUtils;
import com.product.domain.entity.Calendar;
import com.product.domain.entity.Machine;
import com.product.domain.entity.MachineMoldCompatibility;
import com.product.domain.entity.OperationTask;
import com.product.domain.entity.Resource;
import com.product.domain.entity.TaskAssignment;
import com.product.domain.entity.TaskResourceRequirement;
import com.product.pps.dto.ResourceRuntimeStatsDTO;
import com.product.pps.dto.TaskSchedulingPriorityDTO;
import com.product.pps.mapper.TaskAssignmentResourceMapper;
import com.product.pps.enums.SchedulingStrategy;
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
            Map<String, List<String>> postToPredecessors,
            Map<String, LocalDateTime> predecessorEndTimes) {
        List<TaskAssignment> assignments = new ArrayList<>(tasks.size());
        List<String> taskIds = new ArrayList<>(tasks.size());
        Map<String, List<String>> safePostToPredecessors = postToPredecessors == null ? Map.of() : postToPredecessors;
        Map<String, LocalDateTime> safePredecessorEndTimes = predecessorEndTimes == null
                ? new HashMap<>()
                : predecessorEndTimes;

        for (OperationTask task : tasks) {
            if (task == null) {
                continue;
            }

            LocalDateTime dependencyEarliestStart = resolveDependencyEarliestStart(
                    task.getTaskId(), safePostToPredecessors, safePredecessorEndTimes);
            LocalDateTime effectiveEarliestStart = maxTime(task.getEarliestStart(), dependencyEarliestStart);

            // 级联选择：MACHINE -> MOLD -> PERSON
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
            assignment.setPlannedStart(choice.plannedStart);
            assignment.setPlannedEnd(choice.plannedEnd);
            assignment.setSequenceOnResource(choice.sequenceOnResource);

            // 将选中的资源 ID 回写到需求列表，供持久化服务生成 TaskAssignmentResource 明细
            List<TaskResourceRequirement> resolvedRequirements = resolveSelectedResources(
                    task.getResourceRequirementList(), choice.machineId, choice.moldId, choice.personId);
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
            assignment.setResourceSequenceMap(resourceSequenceMap);
            assignments.add(assignment);
            taskIds.add(task.getTaskId());
            safePredecessorEndTimes.put(task.getTaskId(), choice.plannedEnd);

            // 更新内存快照：更新所有选中资源的状态
            if (StringUtils.isNotEmpty(choice.machineId)) {
                runtimeContext.update(ResourceConstants.RESOURCE_TYPE_MACHINE,
                        choice.machineId, choice.plannedEnd, choice.sequenceOnResource);
            }
            if (StringUtils.isNotEmpty(choice.moldId)) {
                runtimeContext.update(ResourceConstants.RESOURCE_TYPE_MOLD,
                        choice.moldId, choice.plannedEnd, choice.moldSequence);
            }
            if (StringUtils.isNotEmpty(choice.personId)) {
                runtimeContext.update(ResourceConstants.RESOURCE_TYPE_PERSON,
                        choice.personId, choice.plannedEnd, choice.personSequence);
            }
        }
        return new ScheduleBatchResult(assignments, taskIds);
    }

    /**
     * 根据 task_dependency 计算后置任务的依赖约束开始时间。
     */
    LocalDateTime resolveDependencyEarliestStart(String taskId,
            Map<String, List<String>> postToPredecessors,
            Map<String, LocalDateTime> predecessorEndTimes) {
        if (StringUtils.isEmpty(taskId) || postToPredecessors == null || predecessorEndTimes == null) {
            return null;
        }
        List<String> preTaskIds = postToPredecessors.get(taskId);
        if (CollectionUtils.isEmpty(preTaskIds)) {
            return null;
        }
        LocalDateTime latestPredecessorEnd = null;
        for (String preTaskId : preTaskIds) {
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
            Map<String, TaskSchedulingPriorityDTO> priorityMap) {
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
        ResourceRuntimeContext context = new ResourceRuntimeContext();
        Set<String> resourceTypes = schedulingContext.getResourcesByType().keySet();
        if (CollectionUtils.isEmpty(resourceTypes)) {
            return context;
        }

        List<ResourceRuntimeStatsDTO> stats = taskAssignmentResourceMapper.selectResourceRuntimeStats(
                new ArrayList<>(resourceTypes));
        if (CollectionUtils.isEmpty(stats)) {
            return context;
        }

        for (ResourceRuntimeStatsDTO row : stats) {
            if (row == null || StringUtils.isEmpty(row.getResourceType())
                    || StringUtils.isEmpty(row.getResourceId())) {
                continue;
            }
            if (row.getLatestEndTime() != null) {
                context.setNextAvailableTime(row.getResourceType(), row.getResourceId(), row.getLatestEndTime());
            }
            if (row.getMaxSequence() != null) {
                context.setNextSequence(row.getResourceType(), row.getResourceId(), row.getMaxSequence() + 1L);
            }
        }
        return context;
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
        List<Resource> machines = schedulingContext.getResourcesByType()
                .getOrDefault(ResourceConstants.RESOURCE_TYPE_MACHINE, List.of());
        Map<Long, Calendar> calendarMap = schedulingContext.getCalendarMap();

        // 阶段1: 选择最优机台
        MachineChoice machineChoice = chooseBestMachine(task, machines, calendarMap, runtimeContext, assignmentStart,
                strategy, effectiveEarliestStart);
        if (machineChoice == null) {
            return null;
        }

        String moldId = null;
        Long moldSequence = null;
        String personId = null;
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
                personId = choosePerson(personReqs, schedulingContext, runtimeContext);
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
                moldId, moldSequence, personId, personSequence);
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
            String machineId, String moldId, String personId) {
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
            copy.setChangeoverTimeMin(req.getChangeoverTimeMin());

            // 回写选中资源 ID（仅当原需求未指定时）
            if (StringUtils.isEmpty(copy.getResourceId())) {
                if (ResourceConstants.RESOURCE_TYPE_MACHINE.equals(copy.getResourceType())
                        && StringUtils.isNotEmpty(machineId)) {
                    copy.setResourceId(machineId);
                } else if (ResourceConstants.RESOURCE_TYPE_MOLD.equals(copy.getResourceType())
                        && StringUtils.isNotEmpty(moldId)) {
                    copy.setResourceId(moldId);
                } else if (ResourceConstants.RESOURCE_TYPE_PERSON.equals(copy.getResourceType())
                        && StringUtils.isNotEmpty(personId)) {
                    copy.setResourceId(personId);
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
            LocalDateTime effectiveEarliestStart) {
        MachineChoice best = null;
        Comparator<MachineChoice> comparator = machineChoiceComparator(strategy);

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

            LocalDateTime candidate = maxTime(effectiveEarliestStart, machineNextTime, assignmentStart);
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

            MachineChoice choice = new MachineChoice(
                    machine.getResourceId(),
                    machine,
                    window.start,
                    window.end,
                    sequenceOnResource,
                    estimateSetupCost(task, machine));

            if (best == null || comparator.compare(choice, best) < 0) {
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
        for (TaskResourceRequirement requirement : task.getResourceRequirementList()) {
            if (requirement == null || !isMandatoryRequirement(requirement)) {
                continue;
            }
            if (ResourceConstants.RESOURCE_TYPE_MACHINE.equals(requirement.getResourceType())
                    && StringUtils.isNotEmpty(requirement.getResourceId())
                    && !StringUtils.equals(requirement.getResourceId(), machine.getResourceId())) {
                return false;
            }
            if (ResourceConstants.RESOURCE_TYPE_MOLD.equals(requirement.getResourceType())
                    && StringUtils.isNotEmpty(requirement.getResourceId())
                    && !machineSupportsMold(machine, requirement.getResourceId())) {
                return false;
            }
        }
        return true;
    }

    private boolean isMandatoryRequirement(TaskResourceRequirement requirement) {
        return requirement.getIsMandatory() == null || requirement.getIsMandatory() != 0;
    }

    private boolean machineSupportsMold(Resource machineResource, String moldId) {
        Machine machine = machineResource.getMachine();
        if (machine == null || CollectionUtils.isEmpty(machine.getMoldCompatibilityList())) {
            return false;
        }
        return machine.getMoldCompatibilityList().stream()
                .filter(Objects::nonNull)
                .anyMatch(item -> StringUtils.equals(item.getMoldId(), moldId)
                        && (item.getIsCompatible() == null || item.getIsCompatible() != 0));
    }

    private Integer estimateSetupCost(OperationTask task, Resource machine) {
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
    private String chooseMold(List<TaskResourceRequirement> moldReqs,
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
            String bestMoldId = null;
            LocalDateTime bestMoldNext = null;
            for (MachineMoldCompatibility compat : machine.getMoldCompatibilityList()) {
                if (compat == null || StringUtils.isEmpty(compat.getMoldId())
                        || (compat.getIsCompatible() != null && compat.getIsCompatible() == 0)) {
                    continue;
                }
                String moldId = compat.getMoldId();
                // 确认模具在可用资源列表中
                boolean isAvailable = moldResources.stream()
                        .anyMatch(r -> r != null && StringUtils.equals(r.getResourceId(), moldId));
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
    private String choosePerson(List<TaskResourceRequirement> personReqs,
            TaskSchedulingQueryService.SchedulingResourceContext schedulingContext,
            ResourceRuntimeContext runtimeContext) {
        List<Resource> personResources = schedulingContext.getResourcesByType()
                .getOrDefault(ResourceConstants.RESOURCE_TYPE_PERSON, List.of());

        for (TaskResourceRequirement req : personReqs) {
            if (req.getResourceId() != null) {
                // 指定了人员ID：验证技能匹配
                if (personHasCapability(req.getResourceId(), req.getCapabilityCode(), personResources)) {
                    return req.getResourceId();
                }
                return null;
            }

            // 未指定人员ID：通过 capabilityCode 匹配
            String capabilityCode = req.getCapabilityCode();
            if (StringUtils.isEmpty(capabilityCode)) {
                continue;
            }
            String bestPersonId = null;
            LocalDateTime bestPersonNext = null;
            for (Resource person : personResources) {
                if (person == null) {
                    continue;
                }
                if (!personHasCapability(person.getResourceId(), capabilityCode, personResources)) {
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

    /**
     * 检查人员是否具备指定的技能（opCode 匹配 + isEnabled=1）。
     */
    private boolean personHasCapability(String personId,
            String capabilityCode,
            List<Resource> personResources) {
        if (StringUtils.isEmpty(personId) || StringUtils.isEmpty(capabilityCode)) {
            return false;
        }
        for (Resource person : personResources) {
            if (person == null || !StringUtils.equals(person.getResourceId(), personId)) {
                continue;
            }
            if (CollectionUtils.isEmpty(person.getCapabilityList())) {
                return false;
            }
            return person.getCapabilityList().stream()
                    .filter(Objects::nonNull)
                    .filter(cap -> Integer.valueOf(1).equals(cap.getIsEnabled()))
                    .anyMatch(cap -> StringUtils.equals(cap.getOpCode(), capabilityCode));
        }
        return false;
    }

    // ========================== 比较器 ==========================

    /**
     * 根据策略构建机台选择比较器。
     */
    private Comparator<MachineChoice> machineChoiceComparator(SchedulingStrategy strategy) {
        if (strategy == SchedulingStrategy.LOWEST_COST) {
            return Comparator
                    .comparing((MachineChoice item) -> item.setupCostMin,
                            Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(item -> item.plannedEnd, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(item -> item.plannedStart, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(item -> item.machineId, Comparator.nullsLast(Comparator.naturalOrder()));
        }
        if (strategy == SchedulingStrategy.EARLIEST_FINISH) {
            return Comparator
                    .comparing((MachineChoice item) -> item.plannedEnd, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(item -> item.plannedStart, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(item -> item.machineId, Comparator.nullsLast(Comparator.naturalOrder()));
        }
        return Comparator
                .comparing((MachineChoice item) -> item.plannedStart, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(item -> item.plannedEnd, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(item -> item.machineId, Comparator.nullsLast(Comparator.naturalOrder()));
    }

    /**
     * 根据策略构建任务排序比较器。
     */
    private Comparator<OperationTask> taskComparator(SchedulingStrategy strategy,
            Map<String, TaskSchedulingPriorityDTO> priorityMap) {
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

    private LocalDateTime priorityDate(OperationTask task, Map<String, TaskSchedulingPriorityDTO> priorityMap) {
        TaskSchedulingPriorityDTO context = priorityMap == null || task == null ? null
                : priorityMap.get(task.getTaskId());
        return context == null ? null : context.getDueDate();
    }

    private Long priorityValue(OperationTask task, Map<String, TaskSchedulingPriorityDTO> priorityMap) {
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
        private final Map<String, Map<String, LocalDateTime>> nextAvailableTimeMap = new HashMap<>();
        /** resourceType -> (resourceId -> 下一个序号) */
        private final Map<String, Map<String, Long>> nextSequenceMap = new HashMap<>();

        /**
         * 获取资源的最早可用时间。
         *
         * @param resourceType 资源类型
         * @param resourceId   资源ID
         * @return 最早可用时间（null 表示该资源从未被占用）
         */
        public LocalDateTime getNextAvailableTime(String resourceType, String resourceId) {
            Map<String, LocalDateTime> typeMap = nextAvailableTimeMap.get(resourceType);
            return typeMap == null ? null : typeMap.get(resourceId);
        }

        /**
         * 获取资源的下一个序号。
         *
         * @param resourceType 资源类型
         * @param resourceId   资源ID
         * @return 下一个序号（不存在则返回 1）
         */
        public Long getNextSequence(String resourceType, String resourceId) {
            Map<String, Long> typeMap = nextSequenceMap.get(resourceType);
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
        public void update(String resourceType, String resourceId, LocalDateTime plannedEnd, Long usedSequence) {
            if (StringUtils.isEmpty(resourceType) || StringUtils.isEmpty(resourceId)) {
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
        void setNextAvailableTime(String resourceType, String resourceId, LocalDateTime time) {
            nextAvailableTimeMap
                    .computeIfAbsent(resourceType, k -> new HashMap<>())
                    .put(resourceId, time);
        }

        /**
         * 设置资源的下一个序号（预加载时使用）。
         */
        void setNextSequence(String resourceType, String resourceId, Long sequence) {
            nextSequenceMap
                    .computeIfAbsent(resourceType, k -> new HashMap<>())
                    .put(resourceId, sequence);
        }
    }

    /**
     * 资源选择结果（扩展 MachineChoice，包含模具和人员信息）。
     */
    public static class ResourceChoice {
        private final String machineId;
        private final LocalDateTime plannedStart;
        private final LocalDateTime plannedEnd;
        private final Long sequenceOnResource;
        private final Integer setupCostMin;
        private final String moldId;
        private final Long moldSequence;
        private final String personId;
        private final Long personSequence;

        public ResourceChoice(String machineId,
                LocalDateTime plannedStart,
                LocalDateTime plannedEnd,
                Long sequenceOnResource,
                Integer setupCostMin,
                String moldId,
                Long moldSequence,
                String personId,
                Long personSequence) {
            this.machineId = machineId;
            this.plannedStart = plannedStart;
            this.plannedEnd = plannedEnd;
            this.sequenceOnResource = sequenceOnResource;
            this.setupCostMin = setupCostMin;
            this.moldId = moldId;
            this.moldSequence = moldSequence;
            this.personId = personId;
            this.personSequence = personSequence;
        }
    }

    /**
     * 机台选择结果（内部使用，包含 Resource 引用以便级联选择时获取兼容模具列表）。
     */
    static class MachineChoice {
        private final String machineId;
        private final Resource machineResource;
        private final LocalDateTime plannedStart;
        private final LocalDateTime plannedEnd;
        private final Long sequenceOnResource;
        private final Integer setupCostMin;

        MachineChoice(String machineId,
                Resource machineResource,
                LocalDateTime plannedStart,
                LocalDateTime plannedEnd,
                Long sequenceOnResource,
                Integer setupCostMin) {
            this.machineId = machineId;
            this.machineResource = machineResource;
            this.plannedStart = plannedStart;
            this.plannedEnd = plannedEnd;
            this.sequenceOnResource = sequenceOnResource;
            this.setupCostMin = setupCostMin;
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
        private final List<String> taskIds;

        public ScheduleBatchResult(List<TaskAssignment> assignments, List<String> taskIds) {
            this.assignments = assignments;
            this.taskIds = taskIds;
        }

        public List<TaskAssignment> getAssignments() {
            return assignments;
        }

        public List<String> getTaskIds() {
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
        private final Map<String, LocalDateTime> nextAvailableTimeMap = new HashMap<>();
        private final Map<String, Long> nextSequenceMap = new HashMap<>();

        public LocalDateTime getNextAvailableTime(String machineId) {
            return nextAvailableTimeMap.get(machineId);
        }

        public Long getNextSequence(String machineId) {
            return nextSequenceMap.getOrDefault(machineId, 1L);
        }

        public void update(String machineId, LocalDateTime plannedEnd, Long usedSequence) {
            if (StringUtils.isNotEmpty(machineId) && plannedEnd != null) {
                nextAvailableTimeMap.put(machineId, plannedEnd);
            }
            if (StringUtils.isNotEmpty(machineId) && usedSequence != null) {
                nextSequenceMap.put(machineId, usedSequence + 1L);
            }
        }
    }
}
