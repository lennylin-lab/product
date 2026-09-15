package com.product.planning.service.impl;

import com.product.planning.common.constant.ResourceConstants;
import com.product.planning.common.constant.StatusConstants;
import com.product.planning.domain.entity.OperationTask;
import com.product.planning.domain.entity.TaskAssignment;
import com.product.planning.domain.entity.TaskAssignmentResource;
import com.product.planning.domain.entity.TaskResourceRequirement;
import com.product.planning.domain.model.Calendar;
import com.product.planning.domain.model.Fixture;
import com.product.planning.domain.model.FixtureMoldCompatibility;
import com.product.planning.domain.model.Machine;
import com.product.planning.domain.model.MachineMoldCompatibility;
import com.product.planning.domain.model.Resource;
import com.product.planning.enums.SchedulingStrategy;
import com.product.planning.route.RouteRuleRegistry;
import com.product.planning.route.model.InjectA2TimeModel;
import com.product.planning.route.model.PostUnitTimeModel;
import com.product.planning.route.model.SetupBaseTimeModel;
import com.product.planning.route.rule.InjectMachineRule;
import com.product.planning.route.rule.PostWorkstationRule;
import com.product.planning.route.rule.SetupMachineRule;
import com.product.planning.mapper.OperationTaskMapper;
import com.product.planning.mapper.TaskAssignmentMapper;
import com.product.planning.mapper.TaskAssignmentResourceMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 夹具占用持久化单测（2026-09-15 夹具占用任务）。
 *
 * <p>沿用 {@link TaskAssignmentPersistenceServiceTest} 既有桩风格（Mockito + ReflectionTestUtils，
 * Testable 子类模拟 saveBatch 的 assignmentId 生成）：验证计算器产出的夹具选择经
 * {@code appendRequirementAssignments(...)} 泛化路径写入 task_assignment_resource 的
 * FIXTURE 行（resource_type/requirement_id/时间窗口/独立序号逐项断言），
 * 持久化服务无需任何夹具特判。</p>
 */
class TaskAssignmentPersistenceServiceFixtureTest {

    /** 排程产出（机台+模具+夹具）→ 持久化：FIXTURE 行经泛化需求路径落库且字段正确 */
    @Test
    void persistBatchResultShouldWriteFixtureRowThroughGenericRequirementPath() {
        TaskAssignmentMapper taskAssignmentMapper = mock(TaskAssignmentMapper.class);
        TaskAssignmentResourceMapper taskAssignmentResourceMapper = mock(TaskAssignmentResourceMapper.class);
        OperationTaskMapper operationTaskMapper = mock(OperationTaskMapper.class);

        TestableTaskAssignmentPersistenceService service = new TestableTaskAssignmentPersistenceService();
        ReflectionTestUtils.setField(service, "baseMapper", taskAssignmentMapper);
        ReflectionTestUtils.setField(service, "taskAssignmentResourceMapper", taskAssignmentResourceMapper);
        ReflectionTestUtils.setField(service, "operationTaskMapper", operationTaskMapper);

        // 先用计算器真实产出含夹具的派工结果（不 mock 持久化侧任何夹具逻辑）
        TaskSchedulingCalculator.ScheduleBatchResult batchResult = scheduleFixtureTask();

        when(taskAssignmentMapper.selectExistingTaskIds(anyList())).thenReturn(List.of());
        when(taskAssignmentResourceMapper.batchInsert(anyList())).thenAnswer(invocation -> {
            List<?> rows = invocation.getArgument(0);
            return rows.size();
        });
        when(operationTaskMapper.batchMarkScheduled(eq(List.of(531L)),
                eq(StatusConstants.READY_OPERATION_TASK),
                eq(StatusConstants.SCHEDULED_OPERATION_TASK))).thenReturn(1);

        assertTrue(service.persistBatchResult(batchResult, 10));

        ArgumentCaptor<List<TaskAssignmentResource>> captor = ArgumentCaptor.forClass(List.class);
        verify(taskAssignmentResourceMapper).batchInsert(captor.capture());
        List<TaskAssignmentResource> rows = captor.getValue();

        TaskAssignmentResource fixtureRow = rows.stream()
                .filter(row -> ResourceConstants.RESOURCE_TYPE_FIXTURE.equals(row.getResourceType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("FIXTURE row missing in task_assignment_resource rows"));

        assertEquals(531L, fixtureRow.getTaskId());
        assertEquals(601L, fixtureRow.getResourceId());
        assertEquals(709L, fixtureRow.getRequirementId());
        // 占用窗口 = 任务 plannedStart..plannedEnd（与模具/人员行一致）
        assertEquals(LocalDateTime.of(2026, 4, 10, 8, 0), fixtureRow.getPlannedStart());
        assertEquals(LocalDateTime.of(2026, 4, 10, 9, 0), fixtureRow.getPlannedEnd());
        // 夹具序号独立于机台序号（来自 resourceSequenceMap 的 FIXTURE 键）
        assertEquals(1L, fixtureRow.getSequenceOnResource());
    }

    /** buildAssignedResources：夹具行使用 FIXTURE 独立序号，而非机台统一序号 */
    @Test
    void buildAssignedResourcesShouldUseFixtureSpecificSequence() {
        TestableTaskAssignmentPersistenceService service = new TestableTaskAssignmentPersistenceService();

        TaskAssignment assignment = new TaskAssignment();
        assignment.setTaskId(531L);
        assignment.setMachineId(103L);
        assignment.setPlannedStart(LocalDateTime.of(2026, 4, 28, 8, 0));
        assignment.setPlannedEnd(LocalDateTime.of(2026, 4, 28, 10, 0));
        assignment.setSequenceOnResource(3L);
        assignment.setResourceSequenceMap(Map.of(
                ResourceConstants.RESOURCE_TYPE_MACHINE, 3L,
                ResourceConstants.RESOURCE_TYPE_FIXTURE, 8L));

        TaskResourceRequirement fixtureReq = new TaskResourceRequirement();
        fixtureReq.setRequirementId(709L);
        fixtureReq.setResourceType(ResourceConstants.RESOURCE_TYPE_FIXTURE);
        fixtureReq.setResourceId(601L);

        assignment.setResourceRequirementList(List.of(fixtureReq));
        assignment.setAssignmentId(9L);

        List<TaskAssignmentResource> resources = service.buildAssignedResources(List.of(assignment));

        assertEquals(2, resources.size());
        TaskAssignmentResource fixtureRow = resources.stream()
                .filter(row -> ResourceConstants.RESOURCE_TYPE_FIXTURE.equals(row.getResourceType()))
                .findFirst().orElseThrow();
        assertEquals(601L, fixtureRow.getResourceId());
        assertEquals(709L, fixtureRow.getRequirementId());
        assertEquals(9L, fixtureRow.getAssignmentId());
        assertEquals(8L, fixtureRow.getSequenceOnResource());
        assertEquals(LocalDateTime.of(2026, 4, 28, 8, 0), fixtureRow.getPlannedStart());
        assertEquals(LocalDateTime.of(2026, 4, 28, 10, 0), fixtureRow.getPlannedEnd());
    }

    /** 用计算器真实级联选择产出一批含夹具的派工结果 */
    private TaskSchedulingCalculator.ScheduleBatchResult scheduleFixtureTask() {
        TaskSchedulingCalculator calculator = new TaskSchedulingCalculator();
        RouteRuleRegistry registry = new RouteRuleRegistry(
                List.of(new SetupMachineRule(), new InjectMachineRule(), new PostWorkstationRule()),
                List.of(new SetupBaseTimeModel(), new PostUnitTimeModel(),
                        new InjectA2TimeModel(new InjectDurationCalculator())));
        ReflectionTestUtils.setField(calculator, "routeRuleRegistry", registry);
        ReflectionTestUtils.setField(calculator, "changeoverCalculator", new ChangeoverCalculator());

        OperationTask task = new OperationTask();
        task.setTaskId(531L);
        task.setEarliestStart(LocalDateTime.of(2026, 4, 10, 8, 0));
        task.setStdDurationMin(60L);

        TaskResourceRequirement machineReq = new TaskResourceRequirement();
        machineReq.setResourceType(ResourceConstants.RESOURCE_TYPE_MACHINE);
        machineReq.setIsMandatory(1);
        TaskResourceRequirement moldReq = new TaskResourceRequirement();
        moldReq.setRequirementId(708L);
        moldReq.setResourceType(ResourceConstants.RESOURCE_TYPE_MOLD);
        moldReq.setResourceId(201L);
        moldReq.setIsMandatory(1);
        TaskResourceRequirement fixtureReq = new TaskResourceRequirement();
        fixtureReq.setRequirementId(709L);
        fixtureReq.setResourceType(ResourceConstants.RESOURCE_TYPE_FIXTURE);
        fixtureReq.setIsMandatory(1);
        task.setResourceRequirementList(List.of(machineReq, moldReq, fixtureReq));

        Resource machine = new Resource();
        machine.setResourceId(101L);
        machine.setResourceType(ResourceConstants.RESOURCE_TYPE_MACHINE);
        machine.setCalendarId(1L);
        Machine machineDetail = new Machine();
        machineDetail.setMachineId(101L);
        MachineMoldCompatibility machineCompat = new MachineMoldCompatibility();
        machineCompat.setMachineId(101L);
        machineCompat.setMoldId(201L);
        machineCompat.setIsCompatible(1);
        machineDetail.setMoldCompatibilityList(List.of(machineCompat));
        machine.setMachine(machineDetail);

        Resource mold = new Resource();
        mold.setResourceId(201L);
        mold.setResourceType(ResourceConstants.RESOURCE_TYPE_MOLD);
        mold.setCalendarId(1L);

        Resource fixtureResource = new Resource();
        fixtureResource.setResourceId(601L);
        fixtureResource.setResourceType(ResourceConstants.RESOURCE_TYPE_FIXTURE);
        Fixture fixture = new Fixture();
        fixture.setFixtureId(601L);
        fixture.setFixtureCode("FJ-601");
        FixtureMoldCompatibility fixtureCompat = new FixtureMoldCompatibility();
        fixtureCompat.setFixtureId(601L);
        fixtureCompat.setMoldId(201L);
        fixtureCompat.setIsCompatible(1);
        fixture.setMoldCompatibilityList(List.of(fixtureCompat));
        fixtureResource.setFixture(fixture);

        Map<String, List<Resource>> resourcesByType = new HashMap<>();
        resourcesByType.put(ResourceConstants.RESOURCE_TYPE_MACHINE, List.of(machine));
        resourcesByType.put(ResourceConstants.RESOURCE_TYPE_MOLD, List.of(mold));
        resourcesByType.put(ResourceConstants.RESOURCE_TYPE_FIXTURE, List.of(fixtureResource));

        Calendar calendar = new Calendar();
        calendar.setCalendarId(1L);
        calendar.setWorkdayPattern("Mon,Tue,Wed,Thu,Fri,Sat,Sun");
        calendar.setShiftStart("08:00");
        calendar.setShiftEnd("17:00");

        TaskSchedulingQueryService.SchedulingResourceContext schedulingContext =
                new TaskSchedulingQueryService.SchedulingResourceContext(
                        List.of(machine, mold, fixtureResource), resourcesByType, Map.of(1L, calendar),
                        null, Map.of());

        return calculator.calculateBatchAssignments(
                List.of(task), schedulingContext, new TaskSchedulingCalculator.ResourceRuntimeContext(),
                LocalDateTime.of(2026, 4, 10, 8, 0), SchedulingStrategy.EARLIEST_START);
    }

    private static class TestableTaskAssignmentPersistenceService extends TaskAssignmentPersistenceService {
        @Override
        public boolean saveBatch(java.util.Collection<TaskAssignment> entityList, int batchSize) {
            long id = 1L;
            for (TaskAssignment assignment : entityList) {
                assignment.setAssignmentId(id++);
            }
            return true;
        }
    }
}
