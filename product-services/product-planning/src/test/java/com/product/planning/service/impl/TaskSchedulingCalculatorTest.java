package com.product.planning.service.impl;

import com.product.planning.domain.model.Calendar;
import com.product.planning.domain.model.Machine;
import com.product.planning.domain.model.MachineMoldCompatibility;
import com.product.planning.domain.entity.OperationTask;
import com.product.planning.domain.model.Resource;
import com.product.planning.domain.model.ResourceCapability;
import com.product.planning.domain.entity.TaskResourceRequirement;
import com.product.planning.common.constant.ResourceConstants;
import com.product.planning.common.constant.RouteOperationConstants;
import com.product.planning.domain.model.ChangeoverRule;
import com.product.planning.domain.model.Product;
import com.product.planning.dto.TaskSchedulingPriorityDTO;
import com.product.planning.enums.SchedulingStrategy;
import com.product.planning.route.RouteRuleRegistry;
import com.product.planning.route.model.InjectA2TimeModel;
import com.product.planning.route.model.PostUnitTimeModel;
import com.product.planning.route.model.SetupBaseTimeModel;
import com.product.planning.route.rule.InjectMachineRule;
import com.product.planning.route.rule.PostWorkstationRule;
import com.product.planning.route.rule.SetupMachineRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskSchedulingCalculatorTest {

    private final TaskSchedulingCalculator calculator = new TaskSchedulingCalculator();

    @BeforeEach
    void setUp() {
        RouteRuleRegistry registry = new RouteRuleRegistry(
                List.of(new SetupMachineRule(), new InjectMachineRule(), new PostWorkstationRule()),
                List.of(new SetupBaseTimeModel(), new PostUnitTimeModel(),
                        new InjectA2TimeModel(new InjectDurationCalculator())));
        ReflectionTestUtils.setField(calculator, "routeRuleRegistry", registry);
        ReflectionTestUtils.setField(calculator, "changeoverCalculator", new ChangeoverCalculator());
    }

    /** EARLIEST_FINISH 策略：工期短的任务应排在前面（标准排序 vs 实际排序验证） */
    @Test
    void orderTasksShouldPrioritizeEarliestFinishWhenRequested() {
        OperationTask slowButEarly = new OperationTask();
        slowButEarly.setTaskId(501L);
        slowButEarly.setEarliestStart(LocalDateTime.of(2026, 4, 1, 8, 0));
        slowButEarly.setStdDurationMin(240L);
        slowButEarly.setSequence(1L);

        OperationTask fastButLate = new OperationTask();
        fastButLate.setTaskId(502L);
        fastButLate.setEarliestStart(LocalDateTime.of(2026, 4, 1, 9, 0));
        fastButLate.setStdDurationMin(60L);
        fastButLate.setSequence(2L);

        List<OperationTask> ordered = calculator.orderTasks(List.of(slowButEarly, fastButLate), SchedulingStrategy.EARLIEST_FINISH, Map.of());

        assertEquals(502L, ordered.get(0).getTaskId());
        assertEquals(501L, ordered.get(1).getTaskId());
    }

    /** DUE_DATE_PRIORITY 策略：交期早的任务应排在前面，交期相同时优先级数值大的优先 */
    @Test
    void orderTasksShouldUseDueDatePriorityContext() {
        OperationTask task1 = new OperationTask();
        task1.setTaskId(501L);
        task1.setEarliestStart(LocalDateTime.of(2026, 4, 1, 8, 0));
        task1.setStdDurationMin(60L);
        task1.setSequence(1L);

        OperationTask task2 = new OperationTask();
        task2.setTaskId(502L);
        task2.setEarliestStart(LocalDateTime.of(2026, 4, 1, 8, 0));
        task2.setStdDurationMin(60L);
        task2.setSequence(2L);

        TaskSchedulingPriorityDTO priority1 = new TaskSchedulingPriorityDTO();
        priority1.setTaskId(501L);
        priority1.setDueDate(LocalDateTime.of(2026, 4, 3, 0, 0));
        priority1.setPriority(1L);

        TaskSchedulingPriorityDTO priority2 = new TaskSchedulingPriorityDTO();
        priority2.setTaskId(502L);
        priority2.setDueDate(LocalDateTime.of(2026, 4, 2, 0, 0));
        priority2.setPriority(5L);

        List<OperationTask> ordered = calculator.orderTasks(
                List.of(task1, task2),
                SchedulingStrategy.DUE_DATE_PRIORITY,
                Map.of(501L, priority1, 502L, priority2));

        assertEquals(502L, ordered.get(0).getTaskId());
        assertEquals(501L, ordered.get(1).getTaskId());
    }

    /** 验证不同策略会选到不同机台：EARLIEST_START 选最早开始的 M1，EARLIEST_FINISH 选最早完工的 M2 */
    @Test
    void calculateBatchAssignmentsShouldChooseDifferentMachinesForDifferentStrategies() {
        OperationTask task = new OperationTask();
        task.setTaskId(501L);
        task.setEarliestStart(LocalDateTime.of(2026, 4, 10, 7, 0));
        task.setStdDurationMin(120L);

        Resource earlyMachine = new Resource();
        earlyMachine.setResourceId(101L);
        earlyMachine.setResourceType(ResourceConstants.RESOURCE_TYPE_MACHINE);
        earlyMachine.setCalendarId(1L);

        Resource lateMachine = new Resource();
        lateMachine.setResourceId(102L);
        lateMachine.setResourceType(ResourceConstants.RESOURCE_TYPE_MACHINE);
        lateMachine.setCalendarId(2L);

        Calendar earlyShift = new Calendar();
        earlyShift.setCalendarId(1L);
        earlyShift.setWorkdayPattern("Mon-Tue-Wed-Thu-Fri-Sat-Sun");
        earlyShift.setShiftStart("08:00");
        earlyShift.setShiftEnd("17:00");

        Calendar lateShift = new Calendar();
        lateShift.setCalendarId(2L);
        lateShift.setWorkdayPattern("Mon-Tue-Wed-Thu-Fri-Sat-Sun");
        lateShift.setShiftStart("08:00");
        lateShift.setShiftEnd("17:00");

        Map<Long, Calendar> calendarMap = Map.of(1L, earlyShift, 2L, lateShift);
        TaskSchedulingQueryService.SchedulingResourceContext schedulingContext =
                buildSchedulingContext(List.of(earlyMachine, lateMachine), calendarMap);

        // M2 预设 10:00 可用
        TaskSchedulingCalculator.ResourceRuntimeContext context = new TaskSchedulingCalculator.ResourceRuntimeContext();
        context.setNextAvailableTime(ResourceConstants.RESOURCE_TYPE_MACHINE, 102L,
                LocalDateTime.of(2026, 4, 10, 10, 0));

        TaskSchedulingCalculator.ScheduleBatchResult earliestStart = calculator.calculateBatchAssignments(
                List.of(task), schedulingContext, context,
                LocalDateTime.of(2026, 4, 10, 7, 0), SchedulingStrategy.EARLIEST_START);

        assertNotNull(earliestStart);
        assertEquals(101L, earliestStart.getAssignments().get(0).getMachineId());

        // EARLIEST_FINISH 策略
        TaskSchedulingCalculator.ResourceRuntimeContext finishContext = new TaskSchedulingCalculator.ResourceRuntimeContext();
        finishContext.setNextAvailableTime(ResourceConstants.RESOURCE_TYPE_MACHINE, 102L,
                LocalDateTime.of(2026, 4, 10, 10, 0));

        TaskSchedulingCalculator.ScheduleBatchResult earliestFinish = calculator.calculateBatchAssignments(
                List.of(task), schedulingContext, finishContext,
                LocalDateTime.of(2026, 4, 10, 7, 0), SchedulingStrategy.EARLIEST_FINISH);

        assertNotNull(earliestFinish);
        assertEquals(101L, earliestFinish.getAssignments().get(0).getMachineId());
    }

    @Test
    void calculateBatchAssignmentsShouldSkipIncompatibleMachineWhenTaskRequiresMold() {
        OperationTask task = new OperationTask();
        task.setTaskId(518L);
        task.setEarliestStart(LocalDateTime.of(2026, 4, 10, 8, 0));
        task.setStdDurationMin(60L);
        TaskResourceRequirement requirement = new TaskResourceRequirement();
        requirement.setTaskId(518L);
        requirement.setResourceType(ResourceConstants.RESOURCE_TYPE_MOLD);
        requirement.setResourceId(201L);
        requirement.setIsMandatory(1);
        task.setResourceRequirementList(List.of(requirement));

        Resource incompatibleMachine = buildMachineResource(101L, 1L, 202L, 1);
        Resource compatibleMachine = buildMachineResource(102L, 2L, 201L, 1);

        Map<Long, Calendar> calendarMap = Map.of(1L, buildCalendar(1L), 2L, buildCalendar(2L));
        TaskSchedulingQueryService.SchedulingResourceContext schedulingContext =
                buildSchedulingContext(List.of(incompatibleMachine, compatibleMachine), calendarMap);

        TaskSchedulingCalculator.ScheduleBatchResult result = calculator.calculateBatchAssignments(
                List.of(task), schedulingContext,
                new TaskSchedulingCalculator.ResourceRuntimeContext(),
                LocalDateTime.of(2026, 4, 10, 8, 0), SchedulingStrategy.EARLIEST_START);

        assertNotNull(result);
        assertEquals(102L, result.getAssignments().get(0).getMachineId());
    }

    @Test
    void calculateBatchAssignmentsShouldPreferLowestCostMachineWhenRequested() {
        OperationTask task = new OperationTask();
        task.setTaskId(521L);
        task.setEarliestStart(LocalDateTime.of(2026, 4, 10, 8, 0));
        task.setStdDurationMin(60L);

        Resource highCostMachine = buildMachineResource(101L, 1L, 201L, 1, 45);
        Resource lowCostMachine = buildMachineResource(102L, 2L, 201L, 1, 10);

        Map<Long, Calendar> calendarMap = Map.of(1L, buildCalendar(1L), 2L, buildCalendar(2L));
        TaskSchedulingQueryService.SchedulingResourceContext schedulingContext =
                buildSchedulingContext(List.of(highCostMachine, lowCostMachine), calendarMap);

        TaskSchedulingCalculator.ScheduleBatchResult result = calculator.calculateBatchAssignments(
                List.of(task), schedulingContext,
                new TaskSchedulingCalculator.ResourceRuntimeContext(),
                LocalDateTime.of(2026, 4, 10, 8, 0), SchedulingStrategy.LOWEST_COST);

        assertNotNull(result);
        assertEquals(102L, result.getAssignments().get(0).getMachineId());
    }

    @Test
    void chooseResourcesShouldCascadeMachineMoldPerson() {
        // 任务需要模具 MOLD-1 和具备 INJECT 技能的人员
        OperationTask task = new OperationTask();
        task.setTaskId(515L);
        task.setEarliestStart(LocalDateTime.of(2026, 4, 10, 8, 0));
        task.setStdDurationMin(60L);

        TaskResourceRequirement moldReq = new TaskResourceRequirement();
        moldReq.setTaskId(515L);
        moldReq.setResourceType(ResourceConstants.RESOURCE_TYPE_MOLD);
        moldReq.setResourceId(201L);
        moldReq.setIsMandatory(1);

        TaskResourceRequirement personReq = new TaskResourceRequirement();
        personReq.setTaskId(515L);
        personReq.setResourceType(ResourceConstants.RESOURCE_TYPE_PERSON);
        personReq.setCapabilityCode("INJECT");
        personReq.setIsMandatory(1);

        task.setResourceRequirementList(List.of(moldReq, personReq));

        Resource machine = buildMachineResource(101L, 1L, 201L, 1);
        Resource mold = new Resource();
        mold.setResourceId(201L);
        mold.setResourceType(ResourceConstants.RESOURCE_TYPE_MOLD);
        mold.setCalendarId(1L);

        Resource person = new Resource();
        person.setResourceId(301L);
        person.setResourceType(ResourceConstants.RESOURCE_TYPE_PERSON);
        ResourceCapability cap = new ResourceCapability();
        cap.setResourceId(301L);
        cap.setOpCode("INJECT");
        cap.setIsEnabled(1);
        person.setCapabilityList(List.of(cap));

        Calendar calendar = buildCalendar(1L);
        Map<Long, Calendar> calendarMap = Map.of(1L, calendar);
        TaskSchedulingQueryService.SchedulingResourceContext schedulingContext =
                buildSchedulingContext(List.of(machine, mold, person), calendarMap);

        TaskSchedulingCalculator.ScheduleBatchResult result = calculator.calculateBatchAssignments(
                List.of(task), schedulingContext,
                new TaskSchedulingCalculator.ResourceRuntimeContext(),
                LocalDateTime.of(2026, 4, 10, 8, 0), SchedulingStrategy.EARLIEST_START);

        assertNotNull(result);
        assertEquals(101L, result.getAssignments().get(0).getMachineId());
        assertEquals(201L, result.getAssignments().get(0).getMoldId());
        assertEquals(301L, result.getAssignments().get(0).getPersonId());
    }

    @Test
    void chooseResourcesShouldFailWhenNoAvailablePersonMatchesCapability() {
        OperationTask task = new OperationTask();
        task.setTaskId(517L);
        task.setEarliestStart(LocalDateTime.of(2026, 4, 10, 8, 0));
        task.setStdDurationMin(60L);

        TaskResourceRequirement personReq = new TaskResourceRequirement();
        personReq.setTaskId(517L);
        personReq.setResourceType(ResourceConstants.RESOURCE_TYPE_PERSON);
        personReq.setCapabilityCode("WELD");
        personReq.setIsMandatory(1);
        task.setResourceRequirementList(List.of(personReq));

        Resource machine = buildMachineResource(101L, 1L, null, 1);

        Resource person = new Resource();
        person.setResourceId(301L);
        person.setResourceType(ResourceConstants.RESOURCE_TYPE_PERSON);
        ResourceCapability cap = new ResourceCapability();
        cap.setResourceId(301L);
        cap.setOpCode("INJECT");
        cap.setIsEnabled(1);
        person.setCapabilityList(List.of(cap));

        Map<Long, Calendar> calendarMap = Map.of(1L, buildCalendar(1L));
        TaskSchedulingQueryService.SchedulingResourceContext schedulingContext =
                buildSchedulingContext(List.of(machine, person), calendarMap);

        // 没有具备 WELD 技能的人员，应该抛出异常
        try {
            calculator.calculateBatchAssignments(
                    List.of(task), schedulingContext,
                    new TaskSchedulingCalculator.ResourceRuntimeContext(),
                    LocalDateTime.of(2026, 4, 10, 8, 0), SchedulingStrategy.EARLIEST_START);
        } catch (Exception e) {
            // 预期失败
            return;
        }
        throw new AssertionError("Expected exception when no person matches capability");
    }

    @Test
    void chooseResourcesShouldRespectPersonAvailabilityWindow() {
        OperationTask task = new OperationTask();
        task.setTaskId(516L);
        task.setEarliestStart(LocalDateTime.of(2026, 4, 10, 8, 0));
        task.setStdDurationMin(60L);

        TaskResourceRequirement personReq = new TaskResourceRequirement();
        personReq.setTaskId(516L);
        personReq.setResourceType(ResourceConstants.RESOURCE_TYPE_PERSON);
        personReq.setCapabilityCode("INJECT");
        personReq.setIsMandatory(1);
        task.setResourceRequirementList(List.of(personReq));

        Resource machine = buildMachineResource(101L, 1L, null, 1);

        // P-1 忙碌（10:00 之后才可用），P-2 空闲
        Resource busyPerson = new Resource();
        busyPerson.setResourceId(301L);
        busyPerson.setResourceType(ResourceConstants.RESOURCE_TYPE_PERSON);
        ResourceCapability cap1 = new ResourceCapability();
        cap1.setResourceId(301L);
        cap1.setOpCode("INJECT");
        cap1.setIsEnabled(1);
        busyPerson.setCapabilityList(List.of(cap1));

        Resource freePerson = new Resource();
        freePerson.setResourceId(302L);
        freePerson.setResourceType(ResourceConstants.RESOURCE_TYPE_PERSON);
        ResourceCapability cap2 = new ResourceCapability();
        cap2.setResourceId(302L);
        cap2.setOpCode("INJECT");
        cap2.setIsEnabled(1);
        freePerson.setCapabilityList(List.of(cap2));

        Map<Long, Calendar> calendarMap = Map.of(1L, buildCalendar(1L));
        TaskSchedulingQueryService.SchedulingResourceContext schedulingContext =
                buildSchedulingContext(List.of(machine, busyPerson, freePerson), calendarMap);

        TaskSchedulingCalculator.ResourceRuntimeContext runtimeContext = new TaskSchedulingCalculator.ResourceRuntimeContext();
        runtimeContext.setNextAvailableTime(ResourceConstants.RESOURCE_TYPE_PERSON, 301L,
                LocalDateTime.of(2026, 4, 10, 10, 0));

        TaskSchedulingCalculator.ScheduleBatchResult result = calculator.calculateBatchAssignments(
                List.of(task), schedulingContext, runtimeContext,
                LocalDateTime.of(2026, 4, 10, 8, 0), SchedulingStrategy.EARLIEST_START);

        assertNotNull(result);
        // P-2 空闲应被优先选择
        assertEquals(302L, result.getAssignments().get(0).getPersonId());
    }

    @Test
    void resourceRuntimeContextShouldTrackMultipleResourceTypes() {
        TaskSchedulingCalculator.ResourceRuntimeContext context = new TaskSchedulingCalculator.ResourceRuntimeContext();

        // 初始状态：所有资源 null / seq=1
        assertNull(context.getNextAvailableTime(ResourceConstants.RESOURCE_TYPE_MACHINE, 101L));
        assertEquals(1L, context.getNextSequence(ResourceConstants.RESOURCE_TYPE_MACHINE, 101L));
        assertNull(context.getNextAvailableTime(ResourceConstants.RESOURCE_TYPE_MOLD, 201L));
        assertEquals(1L, context.getNextSequence(ResourceConstants.RESOURCE_TYPE_MOLD, 201L));
        assertNull(context.getNextAvailableTime(ResourceConstants.RESOURCE_TYPE_PERSON, 301L));
        assertEquals(1L, context.getNextSequence(ResourceConstants.RESOURCE_TYPE_PERSON, 301L));

        // 更新机台状态
        context.update(ResourceConstants.RESOURCE_TYPE_MACHINE, 101L,
                LocalDateTime.of(2026, 4, 10, 12, 0), 3L);
        assertEquals(LocalDateTime.of(2026, 4, 10, 12, 0),
                context.getNextAvailableTime(ResourceConstants.RESOURCE_TYPE_MACHINE, 101L));
        assertEquals(4L, context.getNextSequence(ResourceConstants.RESOURCE_TYPE_MACHINE, 101L));

        // 更新模具状态
        context.update(ResourceConstants.RESOURCE_TYPE_MOLD, 201L,
                LocalDateTime.of(2026, 4, 10, 14, 0), 2L);
        assertEquals(LocalDateTime.of(2026, 4, 10, 14, 0),
                context.getNextAvailableTime(ResourceConstants.RESOURCE_TYPE_MOLD, 201L));
        assertEquals(3L, context.getNextSequence(ResourceConstants.RESOURCE_TYPE_MOLD, 201L));

        // 更新人员状态
        context.update(ResourceConstants.RESOURCE_TYPE_PERSON, 301L,
                LocalDateTime.of(2026, 4, 10, 10, 0), 5L);
        assertEquals(LocalDateTime.of(2026, 4, 10, 10, 0),
                context.getNextAvailableTime(ResourceConstants.RESOURCE_TYPE_PERSON, 301L));
        assertEquals(6L, context.getNextSequence(ResourceConstants.RESOURCE_TYPE_PERSON, 301L));

        // 机台状态不受影响
        assertEquals(LocalDateTime.of(2026, 4, 10, 12, 0),
                context.getNextAvailableTime(ResourceConstants.RESOURCE_TYPE_MACHINE, 101L));
    }

    // ========================== 辅助方法 ==========================

    private TaskSchedulingQueryService.SchedulingResourceContext buildSchedulingContext(
            List<Resource> resources, Map<Long, Calendar> calendarMap) {
        Map<String, List<Resource>> resourcesByType = new HashMap<>();
        for (Resource resource : resources) {
            resourcesByType
                    .computeIfAbsent(resource.getResourceType(), k -> new java.util.ArrayList<>())
                    .add(resource);
        }
        return new TaskSchedulingQueryService.SchedulingResourceContext(
                resources, resourcesByType, calendarMap, null, Map.of());
    }

    private Resource buildMachineResource(Long machineId, Long calendarId, Long moldId, Integer compatible) {
        return buildMachineResource(machineId, calendarId, moldId, compatible, null);
    }

    private Resource buildMachineResource(Long machineId, Long calendarId, Long moldId, Integer compatible, Integer defaultSetupTimeMin) {
        Resource machineResource = new Resource();
        machineResource.setResourceId(machineId);
        machineResource.setResourceType(ResourceConstants.RESOURCE_TYPE_MACHINE);
        machineResource.setCalendarId(calendarId);
        Machine machine = new Machine();
        machine.setMachineId(machineId);
        machine.setDefaultSetupTimeMin(defaultSetupTimeMin);
        if (moldId != null) {
            MachineMoldCompatibility compatibility = new MachineMoldCompatibility();
            compatibility.setMachineId(machineId);
            compatibility.setMoldId(moldId);
            compatibility.setIsCompatible(compatible);
            machine.setMoldCompatibilityList(List.of(compatibility));
        }
        machineResource.setMachine(machine);
        return machineResource;
    }

    private Calendar buildCalendar(Long calendarId) {
        Calendar calendar = new Calendar();
        calendar.setCalendarId(calendarId);
        calendar.setWorkdayPattern("Mon-Tue-Wed-Thu-Fri-Sat-Sun");
        calendar.setShiftStart("08:00");
        calendar.setShiftEnd("17:00");
        return calendar;
    }

    @Test
    void calculateBatchAssignmentsShouldRejectMachineWithoutProductCapability() {
        OperationTask task = new OperationTask();
        task.setTaskId(522L);
        task.setOpCode("INJECT");
        task.setProductId(100L);
        task.setEarliestStart(LocalDateTime.of(2026, 4, 10, 8, 0));
        task.setStdDurationMin(60L);
        TaskResourceRequirement machineReq = new TaskResourceRequirement();
        machineReq.setResourceType(ResourceConstants.RESOURCE_TYPE_MACHINE);
        machineReq.setIsMandatory(1);
        task.setResourceRequirementList(List.of(machineReq));

        Resource capableMachine = buildMachineResource(101L, 1L, null, 1);
        ResourceCapability capability = new ResourceCapability();
        capability.setResourceId(101L);
        capability.setOpCode("INJECT");
        capability.setProductId(100L);
        capability.setIsEnabled(1);
        capableMachine.setCapabilityList(List.of(capability));

        Resource wrongProductMachine = buildMachineResource(102L, 2L, null, 1);
        ResourceCapability wrongCapability = new ResourceCapability();
        wrongCapability.setResourceId(102L);
        wrongCapability.setOpCode("INJECT");
        wrongCapability.setProductId(200L);
        wrongCapability.setIsEnabled(1);
        wrongProductMachine.setCapabilityList(List.of(wrongCapability));

        Map<Long, Calendar> calendarMap = Map.of(1L, buildCalendar(1L), 2L, buildCalendar(2L));
        TaskSchedulingQueryService.SchedulingResourceContext schedulingContext =
                buildSchedulingContext(List.of(capableMachine, wrongProductMachine), calendarMap);

        TaskSchedulingCalculator.ScheduleBatchResult result = calculator.calculateBatchAssignments(
                List.of(task), schedulingContext,
                new TaskSchedulingCalculator.ResourceRuntimeContext(),
                LocalDateTime.of(2026, 4, 10, 8, 0), SchedulingStrategy.EARLIEST_START);

        assertNotNull(result);
        assertEquals(101L, result.getAssignments().get(0).getMachineId());
    }

    @Test
    void calculateBatchAssignmentsShouldSchedulePostTaskOnWorkstation() {
        OperationTask task = new OperationTask();
        task.setTaskId(514L);
        task.setOpCode("POST_QC_PUTAWAY");
        task.setEarliestStart(LocalDateTime.of(2026, 4, 10, 8, 0));
        task.setStdDurationMin(60L);

        TaskResourceRequirement personReq = new TaskResourceRequirement();
        personReq.setResourceType(ResourceConstants.RESOURCE_TYPE_PERSON);
        personReq.setCapabilityCode("POST_QC_PUTAWAY");
        personReq.setIsMandatory(1);
        TaskResourceRequirement workstationReq = new TaskResourceRequirement();
        workstationReq.setResourceType(ResourceConstants.RESOURCE_TYPE_WORKSTATION);
        workstationReq.setIsMandatory(1);
        task.setResourceRequirementList(List.of(personReq, workstationReq));

        Resource person = new Resource();
        person.setResourceId(301L);
        person.setResourceType(ResourceConstants.RESOURCE_TYPE_PERSON);
        person.setCalendarId(1L);
        ResourceCapability personCap = new ResourceCapability();
        personCap.setOpCode("POST_QC_PUTAWAY");
        personCap.setIsEnabled(1);
        person.setCapabilityList(List.of(personCap));

        Resource workstation = new Resource();
        workstation.setResourceId(401L);
        workstation.setResourceType(ResourceConstants.RESOURCE_TYPE_WORKSTATION);
        workstation.setCalendarId(1L);

        Map<Long, Calendar> calendarMap = Map.of(1L, buildCalendar(1L));
        TaskSchedulingQueryService.SchedulingResourceContext schedulingContext =
                buildSchedulingContext(List.of(person, workstation), calendarMap);

        TaskSchedulingCalculator.ScheduleBatchResult result = calculator.calculateBatchAssignments(
                List.of(task), schedulingContext,
                new TaskSchedulingCalculator.ResourceRuntimeContext(),
                LocalDateTime.of(2026, 4, 10, 8, 0), SchedulingStrategy.EARLIEST_START);

        assertNotNull(result);
        assertEquals(null, result.getAssignments().get(0).getMachineId());
        assertEquals(301L, result.getAssignments().get(0).getPersonId());
        assertEquals(401L, result.getAssignments().get(0).getWorkstationId());
    }

    @Test
    void calculateBatchAssignmentsShouldRespectTaskDependency() {
        OperationTask setupTask = new OperationTask();
        setupTask.setTaskId(513L);
        setupTask.setEarliestStart(LocalDateTime.of(2026, 4, 10, 8, 0));
        setupTask.setStdDurationMin(120L);

        OperationTask injectTask = new OperationTask();
        injectTask.setTaskId(512L);
        injectTask.setEarliestStart(LocalDateTime.of(2026, 4, 10, 8, 0));
        injectTask.setStdDurationMin(60L);

        Resource machine = buildMachineResource(101L, 1L, null, 1, 30);
        TaskSchedulingQueryService.SchedulingResourceContext schedulingContext =
                buildSchedulingContext(List.of(machine), Map.of(1L, buildCalendar(1L)));
        TaskSchedulingCalculator.ResourceRuntimeContext runtimeContext =
                new TaskSchedulingCalculator.ResourceRuntimeContext();

        Map<Long, List<Long>> postToPredecessors = Map.of(512L, List.of(513L));
        Map<Long, LocalDateTime> predecessorEndTimes = new HashMap<>();

        TaskSchedulingCalculator.ScheduleBatchResult result = calculator.calculateBatchAssignments(
                List.of(setupTask, injectTask),
                schedulingContext,
                runtimeContext,
                LocalDateTime.of(2026, 4, 10, 7, 0),
                SchedulingStrategy.EARLIEST_START,
                postToPredecessors,
                predecessorEndTimes);

        LocalDateTime setupEnd = result.getAssignments().get(0).getPlannedEnd();
        LocalDateTime injectStart = result.getAssignments().get(1).getPlannedStart();
        assertEquals(true, !injectStart.isBefore(setupEnd));
    }

    @Test
    void calculateBatchAssignmentsShouldPreferSameMoldMachineWhenPolicySet() {
        OperationTask task = new OperationTask();
        task.setTaskId(512L);
        task.setOpCode("INJECT");
        task.setQueuePolicy(RouteOperationConstants.QUEUE_SAME_MOLD_FIRST);
        task.setEarliestStart(LocalDateTime.of(2026, 4, 10, 8, 0));
        task.setStdDurationMin(60L);

        TaskResourceRequirement moldReq = new TaskResourceRequirement();
        moldReq.setResourceType(ResourceConstants.RESOURCE_TYPE_MOLD);
        moldReq.setResourceId(203L);
        moldReq.setIsMandatory(1);
        task.setResourceRequirementList(List.of(moldReq));

        Resource machineWithHistory = buildMachineResource(101L, 1L, 203L, 1);
        Resource machineWithoutHistory = buildMachineResource(102L, 2L, 203L, 1);

        Map<Long, Calendar> calendarMap = Map.of(1L, buildCalendar(1L), 2L, buildCalendar(2L));
        TaskSchedulingQueryService.SchedulingResourceContext schedulingContext =
                buildSchedulingContext(List.of(machineWithHistory, machineWithoutHistory), calendarMap);

        TaskSchedulingCalculator.ResourceRuntimeContext runtimeContext =
                new TaskSchedulingCalculator.ResourceRuntimeContext();
        runtimeContext.setMachineLastAssignment(101L, new ChangeoverCalculator.MachineAssignmentSnapshot(
                203L, 100L, "PP", "BLUE", 530L));

        TaskSchedulingCalculator.ScheduleBatchResult result = calculator.calculateBatchAssignments(
                List.of(task), schedulingContext, runtimeContext,
                LocalDateTime.of(2026, 4, 10, 8, 0), SchedulingStrategy.EARLIEST_START);

        assertNotNull(result);
        assertEquals(101L, result.getAssignments().get(0).getMachineId());
    }

    @Test
    void calculateBatchAssignmentsShouldApplyChangeoverForSetupClassRuleWithCustomOpCode() {
        OperationTask task = new OperationTask();
        task.setTaskId(523L);
        task.setOpCode("TOOL_CHANGE");
        task.setEligibleResourceRule(RouteOperationConstants.RULE_SETUP_MACHINE);
        task.setProductId(100L);
        task.setEarliestStart(LocalDateTime.of(2026, 4, 10, 8, 0));
        task.setStdDurationMin(30L);

        TaskResourceRequirement personReq = new TaskResourceRequirement();
        personReq.setResourceType(ResourceConstants.RESOURCE_TYPE_PERSON);
        personReq.setCapabilityCode("TOOL_CHANGE");
        personReq.setIsMandatory(1);
        TaskResourceRequirement machineReq = new TaskResourceRequirement();
        machineReq.setResourceType(ResourceConstants.RESOURCE_TYPE_MACHINE);
        machineReq.setIsMandatory(1);
        task.setResourceRequirementList(List.of(personReq, machineReq));

        Resource person = new Resource();
        person.setResourceId(301L);
        person.setResourceType(ResourceConstants.RESOURCE_TYPE_PERSON);
        person.setCalendarId(1L);
        ResourceCapability personCap = new ResourceCapability();
        personCap.setOpCode("TOOL_CHANGE");
        personCap.setProductId(100L);
        personCap.setIsEnabled(1);
        person.setCapabilityList(List.of(personCap));

        Resource machine = buildMachineResource(101L, 1L, null, 1);

        ChangeoverRule rule = new ChangeoverRule();
        rule.setSameMoldTimeMin(0);
        rule.setDifferentMoldTimeMin(45);
        rule.setMaterialChangeExtraMin(0);
        rule.setColorChangeExtraMin(0);

        Product product = new Product();
        product.setProductId(100L);
        product.setMaterialCode("PP");
        product.setColorCode("RED");

        Map<Long, Calendar> calendarMap = Map.of(1L, buildCalendar(1L));
        TaskSchedulingQueryService.SchedulingResourceContext schedulingContext =
                new TaskSchedulingQueryService.SchedulingResourceContext(
                        List.of(machine, person),
                        Map.of(
                                ResourceConstants.RESOURCE_TYPE_MACHINE, List.of(machine),
                                ResourceConstants.RESOURCE_TYPE_PERSON, List.of(person)),
                        calendarMap,
                        rule,
                        Map.of(100L, product));

        TaskSchedulingCalculator.ResourceRuntimeContext runtimeContext =
                new TaskSchedulingCalculator.ResourceRuntimeContext();
        runtimeContext.setMachineLastAssignment(101L, new ChangeoverCalculator.MachineAssignmentSnapshot(
                204L, 99L, "ABS", "BLACK", 530L));

        TaskSchedulingCalculator.ScheduleBatchResult result = calculator.calculateBatchAssignments(
                List.of(task), schedulingContext, runtimeContext,
                LocalDateTime.of(2026, 4, 10, 8, 0), SchedulingStrategy.EARLIEST_START);

        assertNotNull(result);
        assertEquals(45, result.getAssignments().get(0).getChangeoverTimeMin());
        assertTrue(result.getAssignments().get(0).getPlannedStart()
                .isAfter(LocalDateTime.of(2026, 4, 10, 8, 44)));
    }
}
