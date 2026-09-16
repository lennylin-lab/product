package com.product.planning.service.impl;

import com.product.planning.config.ProductCostModelProperties;
import com.product.planning.domain.model.Calendar;
import com.product.planning.domain.model.ChangeoverRule;
import com.product.planning.domain.model.Machine;
import com.product.planning.domain.model.MachineMoldCompatibility;
import com.product.planning.domain.entity.OperationTask;
import com.product.planning.domain.model.Product;
import com.product.planning.domain.model.Resource;
import com.product.planning.domain.entity.TaskResourceRequirement;
import com.product.planning.common.constant.ResourceConstants;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LOWEST_COST 综合成本模型单测（2026-09-16 成本模型精度提升）：
 * 换模次数派生（有/无历史、同模/换模）、跨班次计数（0/1/多次、窗口恰在边界、非工作日跳过）、
 * 综合成本线性组合（默认权重 + 配置权重 + 能耗槽位恒 0）、比较器主键切换
 * （换模历史驱动选择反转 + 平局键不变 + 其余策略不受影响）。
 */
class TaskSchedulingCostModelTest {

    /** 固定周一（2026-01-05），测试全部使用固定日期保证确定性。 */
    private static final LocalDate MONDAY = LocalDate.of(2026, 1, 5);
    private static final LocalDateTime MON_16_00 = LocalDateTime.of(MONDAY, java.time.LocalTime.of(16, 0));

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

    // ========================== 换模次数派生 ==========================

    @Test
    void changeoverCountShouldBeZeroWithoutAssignmentHistory() {
        OperationTask setupTask = buildTask(601L, "SETUP", null);
        TaskSchedulingCalculator.ResourceRuntimeContext context = new TaskSchedulingCalculator.ResourceRuntimeContext();

        assertEquals(0, invokeChangeoverCount(setupTask, 101L, context), "首任务无历史 → 0（即使 setup 类）");
    }

    @Test
    void changeoverCountShouldCountHistoryOnceWhenNoAdditionalMoldChange() {
        OperationTask injectTask = buildTask(602L, "INJECT", null);
        TaskSchedulingCalculator.ResourceRuntimeContext context = new TaskSchedulingCalculator.ResourceRuntimeContext();
        context.setMachineLastAssignment(101L, snapshot(999L, null, null, null, 601L));

        assertEquals(1, invokeChangeoverCount(injectTask, 101L, context),
                "非 setup 类任务（INJECT 不触发换型）: 仅链上历史记 1");
    }

    @Test
    void changeoverCountShouldAddOneWhenSelectionTriggersMoldChange() {
        OperationTask setupTask = buildTask(603L, "SETUP", null);
        TaskSchedulingCalculator.ResourceRuntimeContext context = new TaskSchedulingCalculator.ResourceRuntimeContext();
        context.setMachineLastAssignment(101L, snapshot(999L, null, null, null, 601L));

        assertEquals(2, invokeChangeoverCount(setupTask, 101L, context),
                "历史记 1 + 本次 setup 类选择模具不同再计 1 → 2");
    }

    @Test
    void changeoverCountShouldStayAtHistoryOnlyWhenPlannedMoldMatchesHistory() {
        OperationTask setupTaskWithSameMold = buildTask(604L, "SETUP", 203L);
        TaskSchedulingCalculator.ResourceRuntimeContext context = new TaskSchedulingCalculator.ResourceRuntimeContext();
        context.setMachineLastAssignment(101L, snapshot(203L, null, null, null, 601L));

        assertEquals(1, invokeChangeoverCount(setupTaskWithSameMold, 101L, context),
                "计划模具与历史同模 → 不再额外计次");
    }

    @Test
    void changeoverCountShouldTreatBothMoldsNullAsChangeMatchingChangeoverCalculator() {
        OperationTask setupTask = buildTask(605L, "SETUP", null);
        TaskSchedulingCalculator.ResourceRuntimeContext context = new TaskSchedulingCalculator.ResourceRuntimeContext();
        // 历史快照无模具（如非模具类派工）：与 ChangeoverCalculator「非双方同模即换模」口径一致
        context.setMachineLastAssignment(101L, snapshot(null, null, null, null, 601L));

        assertEquals(2, invokeChangeoverCount(setupTask, 101L, context));
    }

    // ========================== 跨班次计数 ==========================

    @Test
    void crossShiftCountShouldBeZeroForWindowInsideSingleShift() {
        Calendar calendar = buildCalendar("Mon,Tue,Wed,Thu,Fri,Sat,Sun", "08:00", "17:00");

        assertEquals(0, invokeCrossShiftCount(calendar,
                LocalDateTime.of(MONDAY, java.time.LocalTime.of(9, 0)),
                LocalDateTime.of(MONDAY, java.time.LocalTime.of(10, 0))), "窗口全在同一班次内 → 0");
        assertEquals(0, invokeCrossShiftCount(null,
                LocalDateTime.of(MONDAY, java.time.LocalTime.of(9, 0)),
                LocalDateTime.of(MONDAY, java.time.LocalTime.of(10, 0))), "无日历 → 0");
        assertEquals(0, invokeCrossShiftCount(buildCalendar("Mon", "", "17:00"),
                LocalDateTime.of(MONDAY, java.time.LocalTime.of(9, 0)),
                LocalDateTime.of(MONDAY, java.time.LocalTime.of(10, 0))), "缺班次字段 → 0");
        assertEquals(0, invokeCrossShiftCount(buildCalendar("Mon", "08:00", "bad-time"),
                LocalDateTime.of(MONDAY, java.time.LocalTime.of(9, 0)),
                LocalDateTime.of(MONDAY, java.time.LocalTime.of(10, 0))), "解析失败 → 0（沿用空值口径）");
        assertEquals(0, invokeCrossShiftCount(calendar,
                LocalDateTime.of(MONDAY, java.time.LocalTime.of(9, 0)),
                LocalDateTime.of(MONDAY, java.time.LocalTime.of(9, 0))), "零长窗口 → 0");
    }

    @Test
    void crossShiftCountShouldBeZeroWhenWindowExactlyCoversShiftBoundaries() {
        Calendar calendar = buildCalendar("Mon,Tue,Wed,Thu,Fri,Sat,Sun", "08:00", "17:00");

        assertEquals(0, invokeCrossShiftCount(calendar,
                LocalDateTime.of(MONDAY, java.time.LocalTime.of(8, 0)),
                LocalDateTime.of(MONDAY, java.time.LocalTime.of(17, 0))),
                "窗口恰在边界：[08:00,17:00) 两个边界都恰在端点，开区间语义不计");
    }

    @Test
    void crossShiftCountShouldCountSingleBoundaryStrictlyInsideWindow() {
        Calendar calendar = buildCalendar("Mon,Tue,Wed,Thu,Fri,Sat,Sun", "08:00", "17:00");

        assertEquals(1, invokeCrossShiftCount(calendar,
                LocalDateTime.of(MONDAY, java.time.LocalTime.of(16, 30)),
                LocalDateTime.of(MONDAY, java.time.LocalTime.of(17, 30))),
                "17:00 班次结束边界严格在窗口内 → 1");
    }

    @Test
    void crossShiftCountShouldCountMultipleBoundariesAndSkipNonWorkdays() {
        Calendar allDays = buildCalendar("Mon,Tue,Wed,Thu,Fri,Sat,Sun", "08:00", "17:00");
        assertEquals(5, invokeCrossShiftCount(allDays,
                LocalDateTime.of(MONDAY, java.time.LocalTime.of(7, 0)),
                LocalDateTime.of(MONDAY.plusDays(2), java.time.LocalTime.of(10, 0))),
                "周一 07:00 → 周三 10:00：Mon08/Mon17/Tue08/Tue17/Wed08 共 5 个内部边界");

        Calendar monWed = buildCalendar("Mon,Wed", "08:00", "17:00");
        assertEquals(2, invokeCrossShiftCount(monWed, MON_16_00,
                LocalDateTime.of(MONDAY.plusDays(2), java.time.LocalTime.of(9, 0))),
                "Mon,Wed 日历：周一 17:00 + 周三 08:00 → 2（周二非工作日跳过）");
    }

    // ========================== 综合成本线性组合 ==========================

    @Test
    void compositeCostShouldCombineFactorsUnderDefaultWeights() {
        Long cost = ReflectionTestUtils.invokeMethod(calculator, "computeCompositeCost", 40, 2, 1);
        assertNotNull(cost);
        assertEquals(160L, cost, "默认权重：1×40 + 30×2 + 60×1 + wEnergy×0 = 160");
    }

    @Test
    void compositeCostShouldHonorConfiguredWeightsAndReserveZeroEnergyFactor() {
        ProductCostModelProperties props = new ProductCostModelProperties();
        props.setSetupWeight(2);
        props.setChangeoverCountPenalty(0);
        props.setCrossShiftPenalty(10);
        props.setEnergyWeight(999);
        ReflectionTestUtils.setField(calculator, "costModelProperties", props);

        Long cost = ReflectionTestUtils.invokeMethod(calculator, "computeCompositeCost", 40, 2, 1);
        assertNotNull(cost);
        assertEquals(90L, cost, "2×40 + 0×2 + 10×1 + 999×0 = 90：能耗权重虽大，因子恒 0 不影响结果（AC3）");
    }

    // ========================== 比较器：主键切换 + 平局键 ==========================

    @Test
    @SuppressWarnings("unchecked")
    void lowestCostComparatorShouldRankByCompositeCostThenLegacyTieBreakers() {
        OperationTask task = buildTask(610L, "SETUP", null);
        Comparator<TaskSchedulingCalculator.MachineChoice> comparator = ReflectionTestUtils.invokeMethod(
                calculator, "machineChoiceComparator", SchedulingStrategy.LOWEST_COST, task);
        assertNotNull(comparator);

        LocalDateTime d8 = LocalDateTime.of(MONDAY, java.time.LocalTime.of(8, 0));
        LocalDateTime d9 = LocalDateTime.of(MONDAY, java.time.LocalTime.of(9, 0));
        LocalDateTime d10 = LocalDateTime.of(MONDAY, java.time.LocalTime.of(10, 0));
        // 成本构成：101=换型 30 + 跨班 60×2 = 150；102=纯换型 160 —— 跨班次因子主导排序
        TaskSchedulingCalculator.MachineChoice crossShiftDominant =
                choice(101L, 30, d9, d10, 150L);
        TaskSchedulingCalculator.MachineChoice setupDominant =
                choice(102L, 160, d8, d9, 160L);
        assertTrue(comparator.compare(crossShiftDominant, setupDominant) < 0, "compositeCost 150 < 160 先选");
        assertTrue(comparator.compare(setupDominant, crossShiftDominant) > 0);

        // 平局键与切换前一致：composite 相同 → plannedEnd → plannedStart → machineId
        TaskSchedulingCalculator.MachineChoice tieEndLate = choice(103L, 100, d10, d10, 100L);
        TaskSchedulingCalculator.MachineChoice tieEndEarly = choice(104L, 100, d9, d9, 100L);
        assertTrue(comparator.compare(tieEndEarly, tieEndLate) < 0, "composite 平局 → plannedEnd 早者优先");

        TaskSchedulingCalculator.MachineChoice tieStartLate = choice(105L, 100, d9, d10, 100L);
        TaskSchedulingCalculator.MachineChoice tieStartEarly = choice(106L, 100, d8, d10, 100L);
        assertTrue(comparator.compare(tieStartEarly, tieStartLate) < 0, "composite 与 plannedEnd 平局 → plannedStart 早者优先");

        TaskSchedulingCalculator.MachineChoice tieIdHigh = choice(108L, 100, d8, d9, 100L);
        TaskSchedulingCalculator.MachineChoice tieIdLow = choice(107L, 100, d8, d9, 100L);
        assertTrue(comparator.compare(tieIdLow, tieIdHigh) < 0, "再平局 → machineId 小者优先");
    }

    /** 换模历史驱动的选择反转：历史机台换型成本更低，默认权重下换模惩罚使无历史机台胜出。 */
    @Test
    void lowestCostShouldPreferFreshMachineWhenCandidateCarriesMoldChangeHistory() {
        OperationTask task = buildTask(611L, "SETUP", null);
        Resource historyMachine = buildMachineResource(101L, 1L, null, 1, 90);
        Resource freshMachine = buildMachineResource(102L, 1L, null, 1, 90);

        ChangeoverRule rule = new ChangeoverRule();
        rule.setSameMoldTimeMin(5);
        rule.setDifferentMoldTimeMin(40);
        rule.setMaterialChangeExtraMin(0);
        rule.setColorChangeExtraMin(0);

        Map<Long, Calendar> calendarMap = Map.of(1L, buildCalendar("Mon,Tue,Wed,Thu,Fri,Sat,Sun", "08:00", "17:00"));
        TaskSchedulingQueryService.SchedulingResourceContext schedulingContext = buildSchedulingContext(
                List.of(historyMachine, freshMachine), calendarMap, rule);

        LocalDateTime assignmentStart = LocalDateTime.of(MONDAY, java.time.LocalTime.of(8, 0));

        // LOWEST_COST：101 历史（换模 40 → 换型成本 40，换模次数 1+1=2）→ 40+60=100；
        // 102 无历史（机台默认 90）→ 90。compositeCost 主键使 102 胜出（切换前主键 setupCostMin 会选 101）。
        TaskSchedulingCalculator.ResourceRuntimeContext lowestCostContext =
                new TaskSchedulingCalculator.ResourceRuntimeContext();
        lowestCostContext.setMachineLastAssignment(101L, snapshot(999L, null, null, null, 601L));
        lowestCostContext.setNextAvailableTime(ResourceConstants.RESOURCE_TYPE_MACHINE, 101L,
                LocalDateTime.of(MONDAY, java.time.LocalTime.of(7, 0)));

        TaskSchedulingCalculator.ScheduleBatchResult lowestCost = calculator.calculateBatchAssignments(
                List.of(task), schedulingContext, lowestCostContext, assignmentStart, SchedulingStrategy.LOWEST_COST);
        assertNotNull(lowestCost);
        assertEquals(102L, lowestCost.getAssignments().get(0).getMachineId(), "换模惩罚反转选择 → 无历史机台");

        // 对照（同一候选池、同一窗口）：EARLIEST_START 平局键 machineId → 101，
        // 证明候选与时间计算不变，只有成本主键导致反转。
        TaskSchedulingCalculator.ResourceRuntimeContext earliestStartContext =
                new TaskSchedulingCalculator.ResourceRuntimeContext();
        earliestStartContext.setMachineLastAssignment(101L, snapshot(999L, null, null, null, 601L));
        earliestStartContext.setNextAvailableTime(ResourceConstants.RESOURCE_TYPE_MACHINE, 101L,
                LocalDateTime.of(MONDAY, java.time.LocalTime.of(7, 0)));

        TaskSchedulingCalculator.ScheduleBatchResult earliestStart = calculator.calculateBatchAssignments(
                List.of(task), schedulingContext, earliestStartContext, assignmentStart, SchedulingStrategy.EARLIEST_START);
        assertNotNull(earliestStart);
        assertEquals(101L, earliestStart.getAssignments().get(0).getMachineId());
    }

    /** 平局键回归：compositeCost 相同（同换型默认、无历史因子）时仍按 plannedEnd/machineId 决胜。 */
    @Test
    void lowestCostShouldKeepTieBreakersWhenCompositeCostTies() {
        OperationTask task = buildTask(612L, "SETUP", null);
        Resource busyMachine = buildMachineResource(101L, 1L, null, 1, 30);
        Resource freeMachine = buildMachineResource(102L, 1L, null, 1, 30);

        Map<Long, Calendar> calendarMap = Map.of(1L, buildCalendar("Mon,Tue,Wed,Thu,Fri,Sat,Sun", "08:00", "17:00"));
        TaskSchedulingQueryService.SchedulingResourceContext schedulingContext =
                buildSchedulingContext(List.of(busyMachine, freeMachine), calendarMap, null);

        TaskSchedulingCalculator.ResourceRuntimeContext context = new TaskSchedulingCalculator.ResourceRuntimeContext();
        context.setNextAvailableTime(ResourceConstants.RESOURCE_TYPE_MACHINE, 101L,
                LocalDateTime.of(MONDAY, java.time.LocalTime.of(10, 0)));

        TaskSchedulingCalculator.ScheduleBatchResult result = calculator.calculateBatchAssignments(
                List.of(task), schedulingContext, context,
                LocalDateTime.of(MONDAY, java.time.LocalTime.of(8, 0)), SchedulingStrategy.LOWEST_COST);
        assertNotNull(result);
        assertEquals(102L, result.getAssignments().get(0).getMachineId(),
                "composite 相同 → plannedEnd 早者（空闲机台）胜出，与切换前平局口径一致");
    }

    // ========================== 辅助方法 ==========================

    private int invokeChangeoverCount(OperationTask task, Long machineId,
            TaskSchedulingCalculator.ResourceRuntimeContext context) {
        Integer count = ReflectionTestUtils.invokeMethod(calculator, "deriveChangeoverCount",
                task, machineId, context);
        assertNotNull(count);
        return count;
    }

    private int invokeCrossShiftCount(Calendar calendar, LocalDateTime start, LocalDateTime end) {
        Integer count = ReflectionTestUtils.invokeMethod(calculator, "deriveCrossShiftCount",
                calendar, start, end);
        assertNotNull(count);
        return count;
    }

    /** SETUP 类任务（opcode=SETUP 即触发换型判定）；plannedMoldId 非空时附带强制 MOLD 需求。 */
    private OperationTask buildTask(long taskId, String opCode, Long plannedMoldId) {
        OperationTask task = new OperationTask();
        task.setTaskId(taskId);
        task.setOpCode(opCode);
        task.setEarliestStart(LocalDateTime.of(MONDAY, java.time.LocalTime.of(8, 0)));
        task.setStdDurationMin(60L);
        if (plannedMoldId != null) {
            TaskResourceRequirement moldReq = new TaskResourceRequirement();
            moldReq.setTaskId(taskId);
            moldReq.setResourceType(ResourceConstants.RESOURCE_TYPE_MOLD);
            moldReq.setResourceId(plannedMoldId);
            moldReq.setIsMandatory(1);
            task.setResourceRequirementList(List.of(moldReq));
        }
        return task;
    }

    private ChangeoverCalculator.MachineAssignmentSnapshot snapshot(Long moldId, Long productId,
            String materialCode, String colorCode, Long sourceTaskId) {
        return new ChangeoverCalculator.MachineAssignmentSnapshot(
                moldId, productId, materialCode, colorCode, sourceTaskId);
    }

    private TaskSchedulingCalculator.MachineChoice choice(Long machineId, int setupCostMin,
            LocalDateTime plannedStart, LocalDateTime plannedEnd, long compositeCost) {
        return new TaskSchedulingCalculator.MachineChoice(machineId, null, plannedStart, plannedEnd,
                1L, setupCostMin, null, null, false, compositeCost);
    }

    private Resource buildMachineResource(Long machineId, Long calendarId, Long moldId, Integer compatible,
            Integer defaultSetupTimeMin) {
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

    private Calendar buildCalendar(String workdayPattern, String shiftStart, String shiftEnd) {
        Calendar calendar = new Calendar();
        calendar.setCalendarId(1L);
        calendar.setWorkdayPattern(workdayPattern);
        calendar.setShiftStart(shiftStart);
        calendar.setShiftEnd(shiftEnd);
        return calendar;
    }

    private TaskSchedulingQueryService.SchedulingResourceContext buildSchedulingContext(
            List<Resource> resources, Map<Long, Calendar> calendarMap, ChangeoverRule changeoverRule) {
        Map<String, List<Resource>> resourcesByType = new HashMap<>();
        for (Resource resource : resources) {
            resourcesByType
                    .computeIfAbsent(resource.getResourceType(), k -> new java.util.ArrayList<>())
                    .add(resource);
        }
        return new TaskSchedulingQueryService.SchedulingResourceContext(
                resources, resourcesByType, calendarMap, changeoverRule, Map.<Long, Product>of());
    }
}
