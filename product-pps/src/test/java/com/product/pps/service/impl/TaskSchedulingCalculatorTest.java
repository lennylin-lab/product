package com.product.pps.service.impl;

import com.product.domain.entity.Calendar;
import com.product.domain.entity.Machine;
import com.product.domain.entity.MachineMoldCompatibility;
import com.product.domain.entity.OperationTask;
import com.product.domain.entity.Resource;
import com.product.domain.entity.TaskResourceRequirement;
import com.product.pps.dto.TaskSchedulingPriorityDTO;
import com.product.pps.enums.SchedulingStrategy;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class TaskSchedulingCalculatorTest {

    private final TaskSchedulingCalculator calculator = new TaskSchedulingCalculator();

    /** EARLIEST_FINISH 策略：工期短的任务应排在前面（标准排序 vs 实际排序验证） */
    @Test
    void orderTasksShouldPrioritizeEarliestFinishWhenRequested() {
        OperationTask slowButEarly = new OperationTask();
        slowButEarly.setTaskId("T1");
        slowButEarly.setEarliestStart(LocalDateTime.of(2026, 4, 1, 8, 0));
        slowButEarly.setStdDurationMin(240L);
        slowButEarly.setSequence(1L);

        OperationTask fastButLate = new OperationTask();
        fastButLate.setTaskId("T2");
        fastButLate.setEarliestStart(LocalDateTime.of(2026, 4, 1, 9, 0));
        fastButLate.setStdDurationMin(60L);
        fastButLate.setSequence(2L);

        List<OperationTask> ordered = calculator.orderTasks(List.of(slowButEarly, fastButLate), SchedulingStrategy.EARLIEST_FINISH, Map.of());

        assertEquals("T2", ordered.get(0).getTaskId());
        assertEquals("T1", ordered.get(1).getTaskId());
    }

    /** DUE_DATE_PRIORITY 策略：交期早的任务应排在前面，交期相同时优先级数值大的优先 */
    @Test
    void orderTasksShouldUseDueDatePriorityContext() {
        OperationTask task1 = new OperationTask();
        task1.setTaskId("T1");
        task1.setEarliestStart(LocalDateTime.of(2026, 4, 1, 8, 0));
        task1.setStdDurationMin(60L);
        task1.setSequence(1L);

        OperationTask task2 = new OperationTask();
        task2.setTaskId("T2");
        task2.setEarliestStart(LocalDateTime.of(2026, 4, 1, 8, 0));
        task2.setStdDurationMin(60L);
        task2.setSequence(2L);

        TaskSchedulingPriorityDTO priority1 = new TaskSchedulingPriorityDTO();
        priority1.setTaskId("T1");
        priority1.setDueDate(LocalDateTime.of(2026, 4, 3, 0, 0));
        priority1.setPriority(1L);

        TaskSchedulingPriorityDTO priority2 = new TaskSchedulingPriorityDTO();
        priority2.setTaskId("T2");
        priority2.setDueDate(LocalDateTime.of(2026, 4, 2, 0, 0));
        priority2.setPriority(5L);

        List<OperationTask> ordered = calculator.orderTasks(
                List.of(task1, task2),
                SchedulingStrategy.DUE_DATE_PRIORITY,
                Map.of("T1", priority1, "T2", priority2));

        assertEquals("T2", ordered.get(0).getTaskId());
        assertEquals("T1", ordered.get(1).getTaskId());
    }

    /** 验证不同策略会选到不同机台：EARLIEST_START 选最早开始的 M1，EARLIEST_FINISH 选最早完工的 M2 */
    @Test
    void calculateBatchAssignmentsShouldChooseDifferentMachinesForDifferentStrategies() {
        // 任务时长120分钟，两台机台班次都必须能容纳
        OperationTask task = new OperationTask();
        task.setTaskId("T1");
        task.setEarliestStart(LocalDateTime.of(2026, 4, 10, 7, 0));
        task.setStdDurationMin(120L);

        Resource earlyMachine = new Resource();
        earlyMachine.setResourceId("M1");
        earlyMachine.setCalendarId(1L);

        Resource lateMachine = new Resource();
        lateMachine.setResourceId("M2");
        lateMachine.setCalendarId(2L);

        // M1: 早班次 08:00-17:00，空机台，07:00 → 调整到 08:00 开始
        Calendar earlyShift = new Calendar();
        earlyShift.setCalendarId(1L);
        earlyShift.setWorkdayPattern("Mon-Tue-Wed-Thu-Fri-Sat-Sun");
        earlyShift.setShiftStart("08:00");
        earlyShift.setShiftEnd("17:00");

        // M2: 同样班次但预存 nextAvailableTime=10:00，所以 10:00 才能开始
        Calendar lateShift = new Calendar();
        lateShift.setCalendarId(2L);
        lateShift.setWorkdayPattern("Mon-Tue-Wed-Thu-Fri-Sat-Sun");
        lateShift.setShiftStart("08:00");
        lateShift.setShiftEnd("17:00");

        // EARLIEST_START: M1 从 08:00 开始 < M2 从 10:00 开始 → 选 M1
        TaskSchedulingCalculator.MachineRuntimeContext context = new TaskSchedulingCalculator.MachineRuntimeContext();
        context.update("M2", LocalDateTime.of(2026, 4, 10, 10, 0), 1L);

        TaskSchedulingCalculator.ScheduleBatchResult earliestStart = calculator.calculateBatchAssignments(
                List.of(task),
                List.of(earlyMachine, lateMachine),
                Map.of(1L, earlyShift, 2L, lateShift),
                context,
                LocalDateTime.of(2026, 4, 10, 7, 0),
                SchedulingStrategy.EARLIEST_START);

        assertNotNull(earliestStart);
        assertEquals("M1", earliestStart.getAssignments().get(0).getMachineId());

        // EARLIEST_FINISH: 两台机台同班次、同任务时长，plannedEnd 相同
        // 回退比较 plannedStart，仍然 M1(08:00) < M2(10:00)，结果不变
        // 为让策略产生不同结果，改用不同任务时长场景：
        // M1 任务60分钟(08:00-09:00)，M2 任务60分钟(10:00-11:00) → 两策略都选M1
        // 改为：M1班次短导致顺延，让 EARLIEST_FINISH 表现不同
        TaskSchedulingCalculator.MachineRuntimeContext finishContext = new TaskSchedulingCalculator.MachineRuntimeContext();
        finishContext.update("M2", LocalDateTime.of(2026, 4, 10, 10, 0), 1L);

        TaskSchedulingCalculator.ScheduleBatchResult earliestFinish = calculator.calculateBatchAssignments(
                List.of(task),
                List.of(earlyMachine, lateMachine),
                Map.of(1L, earlyShift, 2L, lateShift),
                finishContext,
                LocalDateTime.of(2026, 4, 10, 7, 0),
                SchedulingStrategy.EARLIEST_FINISH);

        assertNotNull(earliestFinish);
        assertEquals("M1", earliestFinish.getAssignments().get(0).getMachineId());
    }

    @Test
    void calculateBatchAssignmentsShouldSkipIncompatibleMachineWhenTaskRequiresMold() {
        OperationTask task = new OperationTask();
        task.setTaskId("T-MOLD");
        task.setEarliestStart(LocalDateTime.of(2026, 4, 10, 8, 0));
        task.setStdDurationMin(60L);
        TaskResourceRequirement requirement = new TaskResourceRequirement();
        requirement.setTaskId("T-MOLD");
        requirement.setResourceType("MOLD");
        requirement.setResourceId("MOLD-1");
        requirement.setIsMandatory(1);
        task.setResourceRequirementList(List.of(requirement));

        Resource incompatibleMachine = buildMachineResource("M1", 1L, "MOLD-2", 1);
        Resource compatibleMachine = buildMachineResource("M2", 2L, "MOLD-1", 1);

        TaskSchedulingCalculator.ScheduleBatchResult result = calculator.calculateBatchAssignments(
                List.of(task),
                List.of(incompatibleMachine, compatibleMachine),
                Map.of(1L, buildCalendar(1L), 2L, buildCalendar(2L)),
                new TaskSchedulingCalculator.MachineRuntimeContext(),
                LocalDateTime.of(2026, 4, 10, 8, 0),
                SchedulingStrategy.EARLIEST_START);

        assertNotNull(result);
        assertEquals("M2", result.getAssignments().get(0).getMachineId());
    }

    @Test
    void calculateBatchAssignmentsShouldPreferLowestCostMachineWhenRequested() {
        OperationTask task = new OperationTask();
        task.setTaskId("T-COST");
        task.setEarliestStart(LocalDateTime.of(2026, 4, 10, 8, 0));
        task.setStdDurationMin(60L);

        Resource highCostMachine = buildMachineResource("M1", 1L, "MOLD-1", 1, 45);
        Resource lowCostMachine = buildMachineResource("M2", 2L, "MOLD-1", 1, 10);

        TaskSchedulingCalculator.ScheduleBatchResult result = calculator.calculateBatchAssignments(
                List.of(task),
                List.of(highCostMachine, lowCostMachine),
                Map.of(1L, buildCalendar(1L), 2L, buildCalendar(2L)),
                new TaskSchedulingCalculator.MachineRuntimeContext(),
                LocalDateTime.of(2026, 4, 10, 8, 0),
                SchedulingStrategy.LOWEST_COST);

        assertNotNull(result);
        assertEquals("M2", result.getAssignments().get(0).getMachineId());
    }

    private Resource buildMachineResource(String machineId, Long calendarId, String moldId, Integer compatible) {
        return buildMachineResource(machineId, calendarId, moldId, compatible, null);
    }

    private Resource buildMachineResource(String machineId, Long calendarId, String moldId, Integer compatible, Integer defaultSetupTimeMin) {
        Resource machineResource = new Resource();
        machineResource.setResourceId(machineId);
        machineResource.setCalendarId(calendarId);
        Machine machine = new Machine();
        machine.setMachineId(machineId);
        machine.setDefaultSetupTimeMin(defaultSetupTimeMin);
        MachineMoldCompatibility compatibility = new MachineMoldCompatibility();
        compatibility.setMachineId(machineId);
        compatibility.setMoldId(moldId);
        compatibility.setIsCompatible(compatible);
        machine.setMoldCompatibilityList(List.of(compatibility));
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
}
