package com.product.planning.service.impl;

import com.product.planning.common.constant.ResourceConstants;
import com.product.planning.common.exception.ServiceException;
import com.product.planning.domain.entity.OperationTask;
import com.product.planning.domain.entity.TaskAssignment;
import com.product.planning.domain.entity.TaskResourceRequirement;
import com.product.planning.domain.model.Calendar;
import com.product.planning.domain.model.Fixture;
import com.product.planning.domain.model.FixtureMoldCompatibility;
import com.product.planning.domain.model.Machine;
import com.product.planning.domain.model.MachineMoldCompatibility;
import com.product.planning.domain.model.Resource;
import com.product.planning.domain.model.ResourceCapability;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 夹具分配与占用计算单测（2026-09-15 夹具占用任务）。
 *
 * <p>沿用 {@link TaskSchedulingCalculatorTest} 既有桩风格（纯 JUnit 5，无 Spring 上下文）：
 * 验证机台分支夹具选择阶段——显式兼容清单过滤（isCompatible=1 才兼容）、最早可用、
 * 夹具可用时间纳入 plannedStart/plannedEnd 重算、序列独立推进、失败语义与既有
 * 资源不可用一致（ServiceException「没有可用资源」）。</p>
 */
class TaskSchedulingCalculatorFixtureTest {

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

    /** 兼容夹具存在且即时可用：选中夹具、窗口不受影响、需求回填与序列/运行时推进正确 */
    @Test
    void calculateBatchAssignmentsShouldSelectCompatibleFixtureAndBackfillRequirement() {
        OperationTask task = buildFixtureTask(531L, null);
        Resource machine = buildMachineResource(101L, 1L, 201L, 1);
        Resource mold = buildMoldResource(201L);
        Resource fixture = buildFixtureResource(601L, List.of(compat(601L, 201L, 1)));

        TaskSchedulingQueryService.SchedulingResourceContext schedulingContext =
                buildSchedulingContext(List.of(machine, mold, fixture), Map.of(1L, buildCalendar(1L)));
        TaskSchedulingCalculator.ResourceRuntimeContext runtimeContext =
                new TaskSchedulingCalculator.ResourceRuntimeContext();

        TaskSchedulingCalculator.ScheduleBatchResult result = calculator.calculateBatchAssignments(
                List.of(task), schedulingContext, runtimeContext,
                LocalDateTime.of(2026, 4, 10, 8, 0), SchedulingStrategy.EARLIEST_START);

        TaskAssignment assignment = result.getAssignments().get(0);
        assertNotNull(assignment);
        assertEquals(101L, assignment.getMachineId());
        assertEquals(201L, assignment.getMoldId());
        // 夹具即时可用 → 窗口不受影响
        assertEquals(LocalDateTime.of(2026, 4, 10, 8, 0), assignment.getPlannedStart());
        assertEquals(LocalDateTime.of(2026, 4, 10, 9, 0), assignment.getPlannedEnd());
        assertEquals(1L, assignment.getResourceSequenceMap().get(ResourceConstants.RESOURCE_TYPE_FIXTURE));

        // 需求副本回填所选夹具 ID（resolve 仅回填 null）
        Long fixtureReqId = findResolvedFixtureRequirementId(assignment);
        assertEquals(601L, findResolvedFixtureResourceId(assignment));
        assertEquals(709L, fixtureReqId);

        // 运行时上下文按 (FIXTURE, fixtureId) 独立推进
        assertEquals(LocalDateTime.of(2026, 4, 10, 9, 0),
                runtimeContext.getNextAvailableTime(ResourceConstants.RESOURCE_TYPE_FIXTURE, 601L));
        assertEquals(2L, runtimeContext.getNextSequence(ResourceConstants.RESOURCE_TYPE_FIXTURE, 601L));
    }

    /** 兼容夹具较晚可用：plannedStart 被推迟到夹具可用时间，plannedEnd 随之重算 */
    @Test
    void calculateBatchAssignmentsShouldDelayTaskUntilFixtureAvailable() {
        OperationTask task = buildFixtureTask(532L, null);
        Resource machine = buildMachineResource(101L, 1L, 201L, 1);
        Resource mold = buildMoldResource(201L);
        Resource fixture = buildFixtureResource(601L, List.of(compat(601L, 201L, 1)));

        TaskSchedulingQueryService.SchedulingResourceContext schedulingContext =
                buildSchedulingContext(List.of(machine, mold, fixture), Map.of(1L, buildCalendar(1L)));
        TaskSchedulingCalculator.ResourceRuntimeContext runtimeContext =
                new TaskSchedulingCalculator.ResourceRuntimeContext();
        runtimeContext.setNextAvailableTime(ResourceConstants.RESOURCE_TYPE_FIXTURE, 601L,
                LocalDateTime.of(2026, 4, 10, 10, 0));

        TaskSchedulingCalculator.ScheduleBatchResult result = calculator.calculateBatchAssignments(
                List.of(task), schedulingContext, runtimeContext,
                LocalDateTime.of(2026, 4, 10, 8, 0), SchedulingStrategy.EARLIEST_START);

        TaskAssignment assignment = result.getAssignments().get(0);
        assertNotNull(assignment);
        assertEquals(LocalDateTime.of(2026, 4, 10, 10, 0), assignment.getPlannedStart());
        assertEquals(LocalDateTime.of(2026, 4, 10, 11, 0), assignment.getPlannedEnd());
        assertEquals(1L, assignment.getResourceSequenceMap().get(ResourceConstants.RESOURCE_TYPE_FIXTURE));
        assertEquals(LocalDateTime.of(2026, 4, 10, 11, 0),
                runtimeContext.getNextAvailableTime(ResourceConstants.RESOURCE_TYPE_FIXTURE, 601L));
    }

    /** 多个兼容夹具时选最早可用的（忙夹具在前、空闲夹具在后 → 选空闲者，窗口不受影响） */
    @Test
    void calculateBatchAssignmentsShouldPickEarliestAvailableCompatibleFixture() {
        OperationTask task = buildFixtureTask(533L, null);
        Resource machine = buildMachineResource(101L, 1L, 201L, 1);
        Resource mold = buildMoldResource(201L);
        Resource busyFixture = buildFixtureResource(602L, List.of(compat(602L, 201L, 1)));
        Resource freeFixture = buildFixtureResource(601L, List.of(compat(601L, 201L, 1)));

        TaskSchedulingQueryService.SchedulingResourceContext schedulingContext =
                buildSchedulingContext(List.of(machine, mold, busyFixture, freeFixture),
                        Map.of(1L, buildCalendar(1L)));
        TaskSchedulingCalculator.ResourceRuntimeContext runtimeContext =
                new TaskSchedulingCalculator.ResourceRuntimeContext();
        runtimeContext.setNextAvailableTime(ResourceConstants.RESOURCE_TYPE_FIXTURE, 602L,
                LocalDateTime.of(2026, 4, 10, 12, 0));

        TaskSchedulingCalculator.ScheduleBatchResult result = calculator.calculateBatchAssignments(
                List.of(task), schedulingContext, runtimeContext,
                LocalDateTime.of(2026, 4, 10, 8, 0), SchedulingStrategy.EARLIEST_START);

        TaskAssignment assignment = result.getAssignments().get(0);
        assertNotNull(assignment);
        assertEquals(601L, findResolvedFixtureResourceId(assignment));
        assertEquals(LocalDateTime.of(2026, 4, 10, 8, 0), assignment.getPlannedStart());
        assertEquals(LocalDateTime.of(2026, 4, 10, 9, 0), assignment.getPlannedEnd());
    }

    /** 预指定夹具 ID：尊重预指定（602 忙也选 602，从其可用时间起算），回填不覆盖预指定值 */
    @Test
    void calculateBatchAssignmentsShouldRespectPreSpecifiedFixtureRequirement() {
        OperationTask task = buildFixtureTask(534L, 602L);
        Resource machine = buildMachineResource(101L, 1L, 201L, 1);
        Resource mold = buildMoldResource(201L);
        Resource freeFixture = buildFixtureResource(601L, List.of(compat(601L, 201L, 1)));
        Resource preSpecified = buildFixtureResource(602L, List.of(compat(602L, 201L, 1)));

        TaskSchedulingQueryService.SchedulingResourceContext schedulingContext =
                buildSchedulingContext(List.of(machine, mold, freeFixture, preSpecified),
                        Map.of(1L, buildCalendar(1L)));
        TaskSchedulingCalculator.ResourceRuntimeContext runtimeContext =
                new TaskSchedulingCalculator.ResourceRuntimeContext();
        runtimeContext.setNextAvailableTime(ResourceConstants.RESOURCE_TYPE_FIXTURE, 602L,
                LocalDateTime.of(2026, 4, 10, 12, 0));

        TaskSchedulingCalculator.ScheduleBatchResult result = calculator.calculateBatchAssignments(
                List.of(task), schedulingContext, runtimeContext,
                LocalDateTime.of(2026, 4, 10, 8, 0), SchedulingStrategy.EARLIEST_START);

        TaskAssignment assignment = result.getAssignments().get(0);
        assertNotNull(assignment);
        assertEquals(602L, findResolvedFixtureResourceId(assignment));
        assertEquals(LocalDateTime.of(2026, 4, 10, 12, 0), assignment.getPlannedStart());
        assertEquals(LocalDateTime.of(2026, 4, 10, 13, 0), assignment.getPlannedEnd());
    }

    /** 无可用夹具资源（快照无 FIXTURE 行）→ 任务不可排程，失败语义与既有资源不可用一致 */
    @Test
    void calculateBatchAssignmentsShouldRejectTaskWhenNoFixtureResourceAvailable() {
        OperationTask task = buildFixtureTask(535L, null);
        Resource machine = buildMachineResource(101L, 1L, 201L, 1);
        Resource mold = buildMoldResource(201L);

        TaskSchedulingQueryService.SchedulingResourceContext schedulingContext =
                buildSchedulingContext(List.of(machine, mold), Map.of(1L, buildCalendar(1L)));

        ServiceException ex = assertThrows(ServiceException.class, () -> calculator.calculateBatchAssignments(
                List.of(task), schedulingContext, new TaskSchedulingCalculator.ResourceRuntimeContext(),
                LocalDateTime.of(2026, 4, 10, 8, 0), SchedulingStrategy.EARLIEST_START));
        assertTrue(ex.getMessage().contains("没有可用资源"));
    }

    /** isCompatible=0 → 显式清单不兼容 → 任务不可排程 */
    @Test
    void calculateBatchAssignmentsShouldRejectTaskWhenFixtureIncompatible() {
        OperationTask task = buildFixtureTask(536L, null);
        Resource machine = buildMachineResource(101L, 1L, 201L, 1);
        Resource mold = buildMoldResource(201L);
        Resource fixture = buildFixtureResource(601L, List.of(compat(601L, 201L, 0)));

        TaskSchedulingQueryService.SchedulingResourceContext schedulingContext =
                buildSchedulingContext(List.of(machine, mold, fixture), Map.of(1L, buildCalendar(1L)));

        ServiceException ex = assertThrows(ServiceException.class, () -> calculator.calculateBatchAssignments(
                List.of(task), schedulingContext, new TaskSchedulingCalculator.ResourceRuntimeContext(),
                LocalDateTime.of(2026, 4, 10, 8, 0), SchedulingStrategy.EARLIEST_START));
        assertTrue(ex.getMessage().contains("没有可用资源"));
    }

    /** 所选模具无任何兼容行（夹具仅兼容其他模具）→ 任务不可排程 */
    @Test
    void calculateBatchAssignmentsShouldRejectTaskWhenCompatibilityRowMissing() {
        OperationTask task = buildFixtureTask(537L, null);
        Resource machine = buildMachineResource(101L, 1L, 201L, 1);
        Resource mold = buildMoldResource(201L);
        Resource fixture = buildFixtureResource(601L, List.of(compat(601L, 202L, 1)));

        TaskSchedulingQueryService.SchedulingResourceContext schedulingContext =
                buildSchedulingContext(List.of(machine, mold, fixture), Map.of(1L, buildCalendar(1L)));

        ServiceException ex = assertThrows(ServiceException.class, () -> calculator.calculateBatchAssignments(
                List.of(task), schedulingContext, new TaskSchedulingCalculator.ResourceRuntimeContext(),
                LocalDateTime.of(2026, 4, 10, 8, 0), SchedulingStrategy.EARLIEST_START));
        assertTrue(ex.getMessage().contains("没有可用资源"));
    }

    /** 夹具适配以模具为裁决粒度：无 MOLD 需求的夹具任务视为不可满足 */
    @Test
    void calculateBatchAssignmentsShouldRejectFixtureTaskWithoutMoldRequirement() {
        OperationTask task = new OperationTask();
        task.setTaskId(538L);
        task.setEarliestStart(LocalDateTime.of(2026, 4, 10, 8, 0));
        task.setStdDurationMin(60L);
        TaskResourceRequirement machineReq = new TaskResourceRequirement();
        machineReq.setResourceType(ResourceConstants.RESOURCE_TYPE_MACHINE);
        machineReq.setIsMandatory(1);
        TaskResourceRequirement fixtureReq = new TaskResourceRequirement();
        fixtureReq.setResourceType(ResourceConstants.RESOURCE_TYPE_FIXTURE);
        fixtureReq.setIsMandatory(1);
        task.setResourceRequirementList(List.of(machineReq, fixtureReq));

        Resource machine = buildMachineResource(101L, 1L, null, 1);
        Resource fixture = buildFixtureResource(601L, List.of(compat(601L, 201L, 1)));

        TaskSchedulingQueryService.SchedulingResourceContext schedulingContext =
                buildSchedulingContext(List.of(machine, fixture), Map.of(1L, buildCalendar(1L)));

        ServiceException ex = assertThrows(ServiceException.class, () -> calculator.calculateBatchAssignments(
                List.of(task), schedulingContext, new TaskSchedulingCalculator.ResourceRuntimeContext(),
                LocalDateTime.of(2026, 4, 10, 8, 0), SchedulingStrategy.EARLIEST_START));
        assertTrue(ex.getMessage().contains("没有可用资源"));
    }

    /** 工位分支不接夹具（MVP 外延）：工位任务携带强制 FIXTURE 需求按不可排程处理 */
    @Test
    void calculateBatchAssignmentsShouldRejectWorkstationTaskWithFixtureRequirement() {
        OperationTask task = new OperationTask();
        task.setTaskId(539L);
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
        TaskResourceRequirement fixtureReq = new TaskResourceRequirement();
        fixtureReq.setResourceType(ResourceConstants.RESOURCE_TYPE_FIXTURE);
        fixtureReq.setIsMandatory(1);
        task.setResourceRequirementList(List.of(personReq, workstationReq, fixtureReq));

        Resource person = new Resource();
        person.setResourceId(301L);
        person.setResourceType(ResourceConstants.RESOURCE_TYPE_PERSON);
        ResourceCapability personCap = new ResourceCapability();
        personCap.setOpCode("POST_QC_PUTAWAY");
        personCap.setIsEnabled(1);
        person.setCapabilityList(List.of(personCap));
        Resource workstation = new Resource();
        workstation.setResourceId(401L);
        workstation.setResourceType(ResourceConstants.RESOURCE_TYPE_WORKSTATION);
        Resource fixture = buildFixtureResource(601L, List.of());

        TaskSchedulingQueryService.SchedulingResourceContext schedulingContext =
                buildSchedulingContext(List.of(person, workstation, fixture), Map.of(1L, buildCalendar(1L)));

        ServiceException ex = assertThrows(ServiceException.class, () -> calculator.calculateBatchAssignments(
                List.of(task), schedulingContext, new TaskSchedulingCalculator.ResourceRuntimeContext(),
                LocalDateTime.of(2026, 4, 10, 8, 0), SchedulingStrategy.EARLIEST_START));
        assertTrue(ex.getMessage().contains("没有可用资源"));
    }

    /** 排程内多任务竞争同一夹具：先到先得，后任务从夹具 nextAvailableTime 起算且序列推进 */
    @Test
    void calculateBatchAssignmentsShouldSerializeCompetingTasksOnSameFixture() {
        OperationTask taskA = buildFixtureTask(541L, null);
        OperationTask taskB = buildFixtureTask(542L, null);
        // 任务 B 用另一套机台/模具（唯一共同约束是夹具），B 的推迟只来自夹具占用
        TaskResourceRequirement moldReqB = taskB.getResourceRequirementList().stream()
                .filter(req -> ResourceConstants.RESOURCE_TYPE_MOLD.equals(req.getResourceType()))
                .findFirst().orElseThrow();
        moldReqB.setResourceId(202L);

        Resource machineA = buildMachineResource(101L, 1L, 201L, 1);
        Resource machineB = buildMachineResource(102L, 2L, 202L, 1);
        Resource moldA = buildMoldResource(201L);
        Resource moldB = buildMoldResource(202L);
        Resource fixture = buildFixtureResource(601L,
                List.of(compat(601L, 201L, 1), compat(601L, 202L, 1)));

        TaskSchedulingQueryService.SchedulingResourceContext schedulingContext =
                buildSchedulingContext(List.of(machineA, machineB, moldA, moldB, fixture),
                        Map.of(1L, buildCalendar(1L), 2L, buildCalendar(2L)));
        TaskSchedulingCalculator.ResourceRuntimeContext runtimeContext =
                new TaskSchedulingCalculator.ResourceRuntimeContext();

        TaskSchedulingCalculator.ScheduleBatchResult result = calculator.calculateBatchAssignments(
                List.of(taskA, taskB), schedulingContext, runtimeContext,
                LocalDateTime.of(2026, 4, 10, 8, 0), SchedulingStrategy.EARLIEST_START);

        TaskAssignment assignmentA = result.getAssignments().get(0);
        TaskAssignment assignmentB = result.getAssignments().get(1);
        // 任务 A 先占用夹具：08:00-09:00，夹具序号 1
        assertEquals(LocalDateTime.of(2026, 4, 10, 8, 0), assignmentA.getPlannedStart());
        assertEquals(1L, assignmentA.getResourceSequenceMap().get(ResourceConstants.RESOURCE_TYPE_FIXTURE));
        // 任务 B 的机台/模具均空闲，推迟完全来自夹具：09:00-10:00，夹具序号 2
        assertEquals(LocalDateTime.of(2026, 4, 10, 9, 0), assignmentB.getPlannedStart());
        assertEquals(LocalDateTime.of(2026, 4, 10, 10, 0), assignmentB.getPlannedEnd());
        assertEquals(2L, assignmentB.getResourceSequenceMap().get(ResourceConstants.RESOURCE_TYPE_FIXTURE));
        assertEquals(LocalDateTime.of(2026, 4, 10, 10, 0),
                runtimeContext.getNextAvailableTime(ResourceConstants.RESOURCE_TYPE_FIXTURE, 601L));
        assertEquals(3L, runtimeContext.getNextSequence(ResourceConstants.RESOURCE_TYPE_FIXTURE, 601L));
    }

    // ========================== 辅助方法 ==========================

    /** 构建携带 MACHINE + MOLD(moldId) + FIXTURE 强制需求行的机台任务 */
    private OperationTask buildFixtureTask(long taskId, Long preSpecifiedFixtureId) {
        OperationTask task = new OperationTask();
        task.setTaskId(taskId);
        task.setEarliestStart(LocalDateTime.of(2026, 4, 10, 8, 0));
        task.setStdDurationMin(60L);

        TaskResourceRequirement machineReq = new TaskResourceRequirement();
        machineReq.setResourceType(ResourceConstants.RESOURCE_TYPE_MACHINE);
        machineReq.setIsMandatory(1);

        TaskResourceRequirement moldReq = new TaskResourceRequirement();
        moldReq.setResourceType(ResourceConstants.RESOURCE_TYPE_MOLD);
        moldReq.setResourceId(201L);
        moldReq.setIsMandatory(1);

        TaskResourceRequirement fixtureReq = new TaskResourceRequirement();
        fixtureReq.setRequirementId(709L);
        fixtureReq.setResourceType(ResourceConstants.RESOURCE_TYPE_FIXTURE);
        fixtureReq.setResourceId(preSpecifiedFixtureId);
        fixtureReq.setIsMandatory(1);

        task.setResourceRequirementList(List.of(machineReq, moldReq, fixtureReq));
        return task;
    }

    private Long findResolvedFixtureResourceId(TaskAssignment assignment) {
        return assignment.getResourceRequirementList().stream()
                .filter(req -> req != null
                        && ResourceConstants.RESOURCE_TYPE_FIXTURE.equals(req.getResourceType()))
                .findFirst()
                .map(TaskResourceRequirement::getResourceId)
                .orElse(null);
    }

    private Long findResolvedFixtureRequirementId(TaskAssignment assignment) {
        return assignment.getResourceRequirementList().stream()
                .filter(req -> req != null
                        && ResourceConstants.RESOURCE_TYPE_FIXTURE.equals(req.getResourceType()))
                .findFirst()
                .map(TaskResourceRequirement::getRequirementId)
                .orElse(null);
    }

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
        Resource machineResource = new Resource();
        machineResource.setResourceId(machineId);
        machineResource.setResourceType(ResourceConstants.RESOURCE_TYPE_MACHINE);
        machineResource.setCalendarId(calendarId);
        Machine machine = new Machine();
        machine.setMachineId(machineId);
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

    private Resource buildMoldResource(Long moldId) {
        Resource moldResource = new Resource();
        moldResource.setResourceId(moldId);
        moldResource.setResourceType(ResourceConstants.RESOURCE_TYPE_MOLD);
        moldResource.setCalendarId(1L);
        return moldResource;
    }

    private Resource buildFixtureResource(Long fixtureId, List<FixtureMoldCompatibility> compatList) {
        Resource fixtureResource = new Resource();
        fixtureResource.setResourceId(fixtureId);
        fixtureResource.setResourceType(ResourceConstants.RESOURCE_TYPE_FIXTURE);
        Fixture fixture = new Fixture();
        fixture.setFixtureId(fixtureId);
        fixture.setFixtureCode("FJ-" + fixtureId);
        fixture.setMoldCompatibilityList(compatList);
        fixtureResource.setFixture(fixture);
        return fixtureResource;
    }

    private FixtureMoldCompatibility compat(long fixtureId, long moldId, int isCompatible) {
        FixtureMoldCompatibility compatibility = new FixtureMoldCompatibility();
        compatibility.setFixtureId(fixtureId);
        compatibility.setMoldId(moldId);
        compatibility.setIsCompatible(isCompatible);
        return compatibility;
    }

    /** 全工作日历（逗号分隔模式可被 isWorkday 正确解析为全周工作日；本测试断言绝对时间） */
    private Calendar buildCalendar(Long calendarId) {
        Calendar calendar = new Calendar();
        calendar.setCalendarId(calendarId);
        calendar.setWorkdayPattern("Mon,Tue,Wed,Thu,Fri,Sat,Sun");
        calendar.setShiftStart("08:00");
        calendar.setShiftEnd("17:00");
        return calendar;
    }
}
