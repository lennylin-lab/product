package mono;

import com.product.common.constant.ResourceConstants;
import com.product.domain.entity.Calendar;
import com.product.domain.entity.ChangeoverRule;
import com.product.domain.entity.Machine;
import com.product.domain.entity.MachineMoldCompatibility;
import com.product.domain.entity.OperationTask;
import com.product.domain.entity.Product;
import com.product.domain.entity.Resource;
import com.product.domain.entity.ResourceCapability;
import com.product.domain.entity.TaskResourceRequirement;
import com.product.pps.dto.TaskSchedulingPriorityDTO;
import com.product.pps.enums.SchedulingStrategy;
import com.product.pps.route.RouteRuleRegistry;
import com.product.pps.route.model.InjectA2TimeModel;
import com.product.pps.route.model.PostUnitTimeModel;
import com.product.pps.route.model.SetupBaseTimeModel;
import com.product.pps.route.rule.InjectMachineRule;
import com.product.pps.route.rule.PostWorkstationRule;
import com.product.pps.route.rule.SetupMachineRule;
import com.product.pps.service.impl.ChangeoverCalculator;
import com.product.pps.service.impl.InjectDurationCalculator;
import com.product.pps.service.impl.TaskSchedulingCalculator;
import com.product.pps.service.impl.TaskSchedulingQueryService;

import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 4 对拍 harness：用单体（当前源码构件）的真实 TaskSchedulingCalculator /
 * TaskSchedulingQueryService.SchedulingResourceContext / ChangeoverCalculator，
 * 对与微服务侧完全相同的固定数据集（master 数据 + 任务 + 资源需求）在进程内执行
 * 排程计算（EARLIEST_START / DUE_DATE_PRIORITY 两轮），输出派工结果 JSON，
 * 供 parity.py 与微服务实测结果逐字段比对。
 *
 * 资源运行时上下文通过反射调用 setNextAvailableTime/setNextSequence 种子化，
 * 与单体 buildResourceRuntimeContext 预加载语义一致（空库 = 全部空闲、序号从 1）。
 */
public class ParityHarness {

    static final DateTimeFormatter F = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    static final LocalDateTime ASSIGN_START = LocalDateTime.of(2026, 9, 15, 9, 0);
    static final LocalDateTime EARLIEST = LocalDateTime.of(2026, 9, 15, 8, 0);

    public static void main(String[] args) throws Exception {
        // ---- 资源上下文（与 SQL 种子一致的 ID/数据）----
        Map<Long, Calendar> calendars = Map.of(1L, calendar(1L));

        Resource m200 = machineResource(200L, 1L, 20L, List.of(mold(200L, 210L), mold(200L, 211L)));
        Resource m201 = machineResource(201L, 1L, 30L, List.of(mold(201L, 210L), mold(201L, 211L)));
        Resource mold210 = plainResource(210L, "MOLD", 1L);
        Resource mold211 = plainResource(211L, "MOLD", 1L);
        Resource p300 = person(300L, List.of(
                cap("SETUP", 100L), cap("INJECT", 100L), cap("POST_QC_PUTAWAY", 100L),
                cap("SETUP", 101L), cap("INJECT", 101L), cap("POST_QC_PUTAWAY", 101L)));
        Resource ws400 = plainResource(400L, "WORKSTATION", 1L);

        List<Resource> resources = new ArrayList<>(List.of(m200, m201, mold210, mold211, p300, ws400));
        Map<String, List<Resource>> byType = new HashMap<>();
        resources.forEach(r -> byType.computeIfAbsent(r.getResourceType(), k -> new ArrayList<>()).add(r));

        ChangeoverRule rule = new ChangeoverRule();
        rule.setRuleId(1L);
        rule.setSameMoldTimeMin(5);
        rule.setDifferentMoldTimeMin(40);
        rule.setMaterialChangeExtraMin(10);
        rule.setColorChangeExtraMin(10);

        Map<Long, Product> products = new HashMap<>();
        products.put(100L, product(100L, "PP", "RED"));
        products.put(101L, product(101L, "ABS", "BLUE"));

        TaskSchedulingQueryService.SchedulingResourceContext ctx = newCtx(resources, byType, calendars, rule, products);

        // ---- 任务（与 generateTask 语义一致：规则构建需求、std 时长、依赖链）----
        // round1: B1 (L1001, P100, qty30)  B2 (L1003, P101, qty20)  EARLIEST_START
        // round2: B3 (L1002, P100, qty40)  B4 (L1004, P101, qty10)  DUE_DATE_PRIORITY
        List<OperationTask> round1 = new ArrayList<>();
        round1.addAll(batch("B1", 100L, 30, 1800L, 9L, 3600L));
        round1.addAll(batch("B2", 101L, 20, 1200L, 3L, 2400L));
        List<OperationTask> round2 = new ArrayList<>();
        round2.addAll(batch("B3", 100L, 40, 2400L, 12L, 4800L));
        round2.addAll(batch("B4", 101L, 10, 600L, 2L, 1200L));

        TaskSchedulingCalculator calculator = realCalculator();

        StringBuilder out = new StringBuilder("{\n");
        out.append("  \"assignmentStart\": \"").append(ASSIGN_START.format(F)).append("\",\n");

        // ---- round 1: EARLIEST_START ----
        TaskSchedulingCalculator.ResourceRuntimeContext runtime = new TaskSchedulingCalculator.ResourceRuntimeContext();
        Map<Long, TaskSchedulingPriorityDTO> pri1 = Map.of();
        List<OperationTask> ordered1 = calculator.orderTasks(round1, SchedulingStrategy.EARLIEST_START, pri1);
        Map<Long, List<Long>> deps = deps(ordered1);
        Map<Long, LocalDateTime> endTimes = new HashMap<>();
        TaskSchedulingCalculator.ScheduleBatchResult r1 = calculator.calculateBatchAssignments(
                ordered1, ctx, runtime, ASSIGN_START, SchedulingStrategy.EARLIEST_START, deps, endTimes);
        out.append("  \"round1\": ").append(toJson(r1, ordered1, "EARLIEST_START")).append(",\n");

        // ---- round 2: DUE_DATE_PRIORITY（B4/O2 交期 09-20 优先于 B3/O1 09-30）----
        // 真实系统语义：第二轮的运行时上下文由 DB 重建 —— selectResourceRuntimeStats
        // （SCHEDULED 行的 max(planned_end)/max(sequence)+1，即第一轮落库结果）与
        // loadMachineLastAssignments（每机台最近派工快照）。此处从第一轮结果推导种子，
        // 与微服务第二轮实测所见状态一致。
        TaskSchedulingCalculator.ResourceRuntimeContext runtime2 = new TaskSchedulingCalculator.ResourceRuntimeContext();
        seedRuntimeFromAssignments(runtime2, r1);
        Map<Long, MachineLastSeed> lastByMachine = lastAssignmentsByMachine(r1, products);
        Map<Long, TaskSchedulingPriorityDTO> pri2 = new HashMap<>();
        for (OperationTask t : round2) {
            TaskSchedulingPriorityDTO dto = new TaskSchedulingPriorityDTO();
            dto.setTaskId(t.getTaskId());
            // L1004->O2(09-20,9) B4；L1002->O1(09-30,5) B3
            boolean isO2 = t.getBatchId().toString().endsWith("4");
            dto.setDueDate(isO2 ? LocalDateTime.of(2026, 9, 20, 0, 0) : LocalDateTime.of(2026, 9, 30, 0, 0));
            dto.setPriority(isO2 ? 9L : 5L);
            pri2.put(t.getTaskId(), dto);
        }
        // 换型：DUE_DATE_PRIORITY 也要挂机台最近派工快照 —— calculateBatchAssignments
        // 从 schedulingContext 无快照、runtime 提供快照（monolith loadMachineLastAssignments
        // 的结果经 buildResourceRuntimeContext(context, machineLastAssignments) 种入 runtime）。
        // 反射种入 machineLastAssignmentMap。
        try {
            java.lang.reflect.Field f = runtime2.getClass().getDeclaredField("machineLastAssignmentMap");
            f.setAccessible(true);
            Map<Long, ChangeoverCalculator.MachineAssignmentSnapshot> map = new HashMap<>();
            for (Map.Entry<Long, MachineLastSeed> en : lastByMachine.entrySet()) {
                MachineLastSeed ls = en.getValue();
                Product pr = products.get(ls.productId);
                map.put(en.getKey(), new ChangeoverCalculator.MachineAssignmentSnapshot(
                        ls.moldId, ls.productId, pr == null ? null : pr.getMaterialCode(),
                        pr == null ? null : pr.getColorCode(), ls.taskId));
            }
            f.set(runtime2, map);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        List<OperationTask> ordered2 = calculator.orderTasks(round2, SchedulingStrategy.DUE_DATE_PRIORITY, pri2);
        Map<Long, List<Long>> deps2 = deps(ordered2);
        Map<Long, LocalDateTime> endTimes2 = plannedEnds(r1);
        TaskSchedulingCalculator.ScheduleBatchResult r2 = calculator.calculateBatchAssignments(
                ordered2, ctx, runtime2, ASSIGN_START, SchedulingStrategy.DUE_DATE_PRIORITY, deps2, endTimes2);
        out.append("  \"round2\": ").append(toJson(r2, ordered2, "DUE_DATE_PRIORITY")).append("\n");
        out.append("}");

        Path target = Path.of(args.length > 0 ? args[0] : "phase4-mono-expected.json");
        Files.writeString(target, out.toString(), StandardCharsets.UTF_8);
        System.out.println("written " + target.toAbsolutePath());
    }

    // ---- helpers ----
    static Map<Long, Product> key(Product p) {
        Map<Long, Product> m = new HashMap<>();
        m.put(p.getProductId(), p);
        return m;
    }

    static Product product(Long id, String material, String color) {
        Product p = new Product();
        p.setProductId(id);
        p.setMaterialCode(material);
        p.setColorCode(color);
        return p;
    }

    static Calendar calendar(Long id) {
        Calendar c = new Calendar();
        c.setCalendarId(id);
        c.setWorkdayPattern("Mon-Sun");
        c.setShiftStart("08:00");
        c.setShiftEnd("20:00");
        return c;
    }

    static MachineMoldCompatibility mold(Long mid, Long moldId) {
        MachineMoldCompatibility c = new MachineMoldCompatibility();
        c.setMachineId(mid);
        c.setMoldId(moldId);
        c.setIsCompatible(1);
        return c;
    }

    static Resource machineResource(Long id, Long calId, Long setupMin, List<MachineMoldCompatibility> comps) {
        Resource r = plainResource(id, "MACHINE", calId);
        Machine machine = new Machine();
        machine.setMachineId(id);
        machine.setDefaultSetupTimeMin(setupMin.intValue());
        machine.setMoldCompatibilityList(comps);
        r.setMachine(machine);
        return r;
    }

    static Resource plainResource(Long id, String type, Long calId) {
        Resource r = new Resource();
        r.setResourceId(id);
        r.setResourceType(type);
        r.setStatus("AVAILABLE");
        r.setCalendarId(calId);
        return r;
    }

    static ResourceCapability cap(String op, Long productId) {
        ResourceCapability c = new ResourceCapability();
        c.setResourceId(300L);
        c.setOpCode(op);
        c.setProductId(productId);
        c.setIsEnabled(1);
        return c;
    }

    static Resource person(Long id, List<ResourceCapability> caps) {
        Resource r = plainResource(id, "PERSON", 1L);
        r.setCapabilityList(caps);
        return r;
    }

    static List<OperationTask> batch(String batchKey, Long productId, long qty,
                                     long setupMin, long injectMin, long postMin) {
        // batchId 用 7000001 + 尾号（B1..B4 -> 1..4），与 SQL 种子一致
        long batchId = 7000000L + Long.parseLong(batchKey.substring(1));
        List<OperationTask> tasks = new ArrayList<>();
        long base = 9000000L + batchId * 10;
        OperationTask setup = task(base + 1, batchId, "SETUP", 1, setupMin, productId, "RULE_SETUP_MACHINE");
        OperationTask inject = task(base + 2, batchId, "INJECT", 2, injectMin, productId, "RULE_INJECT_MACHINE");
        OperationTask post = task(base + 3, batchId, "POST_QC_PUTAWAY", 3, postMin, productId, "RULE_POST_WORKSTATION");
        setup.setResourceRequirementList(List.of(
                req(setup.getTaskId(), "PERSON", null, "SETUP"),
                req(setup.getTaskId(), "MACHINE", null, "SETUP")));
        inject.setResourceRequirementList(List.of(
                req(inject.getTaskId(), "PERSON", null, "INJECT"),
                req(inject.getTaskId(), "MACHINE", null, "INJECT"),
                req(inject.getTaskId(), "MOLD", null, "INJECT")));
        post.setResourceRequirementList(List.of(
                req(post.getTaskId(), "PERSON", null, "POST_QC_PUTAWAY"),
                req(post.getTaskId(), "WORKSTATION", null, "POST_QC_PUTAWAY")));
        // earliest_start 递增（与 generateTask cumulative 语义一致，均早于 assignmentStart）
        setup.setEarliestStart(EARLIEST);
        inject.setEarliestStart(EARLIEST.plusMinutes(setupMin));
        post.setEarliestStart(EARLIEST.plusMinutes(setupMin + injectMin));
        tasks.add(setup);
        tasks.add(inject);
        tasks.add(post);
        tasksById.addAll(tasks);
        return tasks;
    }

    static OperationTask task(long taskId, long batchId, String op, int seq, long std, Long productId, String ruleCode) {
        OperationTask t = new OperationTask();
        t.setTaskId(taskId);
        t.setBatchId(batchId);
        t.setOpCode(op);
        t.setSequence((long) seq);
        t.setStdDurationMin(std);
        t.setStatus("READY");
        t.setProductId(productId);
        t.setEligibleResourceRule(ruleCode);
        return t;
    }

    static TaskResourceRequirement req(long taskId, String type, Long resourceId, String cap) {
        TaskResourceRequirement r = new TaskResourceRequirement();
        r.setTaskId(taskId);
        r.setResourceType(type);
        r.setResourceId(resourceId);
        r.setCapabilityCode(cap);
        r.setRequiredCount(1);
        r.setIsMandatory(1);
        return r;
    }

    static Map<Long, List<Long>> deps(List<OperationTask> ordered) {
        Map<Long, List<Long>> deps = new HashMap<>();
        OperationTask prev = null;
        for (OperationTask t : ordered) {
            if (prev != null && prev.getBatchId().equals(t.getBatchId())) {
                deps.computeIfAbsent(t.getTaskId(), k -> new ArrayList<>()).add(prev.getTaskId());
            }
            prev = t;
        }
        return deps;
    }

    static String toJson(TaskSchedulingCalculator.ScheduleBatchResult result,
                         List<OperationTask> ordered, String strategy) {
        StringBuilder sb = new StringBuilder("{\n");
        sb.append("    \"strategy\": \"").append(strategy).append("\",\n");
        sb.append("    \"orderedTaskIds\": [");
        for (int i = 0; i < ordered.size(); i++) {
            sb.append(ordered.get(i).getTaskId());
            if (i < ordered.size() - 1) sb.append(", ");
        }
        sb.append("],\n    \"assignments\": [");
        List<com.product.domain.entity.TaskAssignment> as = result.getAssignments();
        for (int i = 0; i < as.size(); i++) {
            com.product.domain.entity.TaskAssignment a = as.get(i);
            sb.append("\n      {\"taskId\": ").append(a.getTaskId())
                    .append(", \"machineId\": ").append(a.getMachineId())
                    .append(", \"moldId\": ").append(a.getMoldId())
                    .append(", \"personId\": ").append(a.getPersonId())
                    .append(", \"workstationId\": ").append(a.getWorkstationId())
                    .append(", \"plannedStart\": \"").append(a.getPlannedStart().format(F))
                    .append("\", \"plannedEnd\": \"").append(a.getPlannedEnd().format(F))
                    .append("\", \"sequenceOnResource\": ").append(a.getSequenceOnResource())
                    .append(", \"changeoverTimeMin\": ").append(a.getChangeoverTimeMin())
                    .append(", \"personSequence\": ").append(seq(a, "PERSON"))
                    .append(", \"moldSequence\": ").append(seq(a, "MOLD"))
                    .append(", \"workstationSequence\": ").append(seq(a, "WORKSTATION"))
                    .append("}");
            if (i < as.size() - 1) sb.append(",");
        }
        sb.append("\n    ]\n  }");
        return sb.toString();
    }

    static class MachineLastSeed {
        Long taskId;
        Long moldId;
        Long productId;
    }

    /** 从第一轮派工结果推导每机台最近派工快照（monolith loadMachineLastAssignments 语义）。 */
    static Map<Long, MachineLastSeed> lastAssignmentsByMachine(TaskSchedulingCalculator.ScheduleBatchResult r1,
            Map<Long, Product> products) {
        Map<Long, MachineLastSeed> byMachine = new HashMap<>();
        Map<Long, LocalDateTime> ends = new HashMap<>();
        for (com.product.domain.entity.TaskAssignment a : r1.getAssignments()) {
            if (a.getMachineId() == null) {
                continue;
            }
            LocalDateTime end = ends.get(a.getMachineId());
            if (end == null || a.getPlannedEnd().isAfter(end)) {
                ends.put(a.getMachineId(), a.getPlannedEnd());
                MachineLastSeed seed = byMachine.computeIfAbsent(a.getMachineId(), k -> new MachineLastSeed());
                seed.taskId = a.getTaskId();
                seed.moldId = a.getMoldId();
                for (OperationTask t : tasksById) {
                    if (t.getTaskId().equals(a.getTaskId())) {
                        seed.productId = t.getProductId();
                    }
                }
            }
        }
        return byMachine;
    }

    static List<OperationTask> tasksById = new ArrayList<>();

    /** 第一轮派工的资源终点/序号（selectResourceRuntimeStats 等价：SCHEDULED 行 max 聚合）。 */
    static void seedRuntimeFromAssignments(TaskSchedulingCalculator.ResourceRuntimeContext runtime,
            TaskSchedulingCalculator.ScheduleBatchResult r1) {
        try {
            java.lang.reflect.Method setT = runtime.getClass().getDeclaredMethod("setNextAvailableTime",
                    String.class, Long.class, LocalDateTime.class);
            java.lang.reflect.Method setS = runtime.getClass().getDeclaredMethod("setNextSequence",
                    String.class, Long.class, Long.class);
            setT.setAccessible(true);
            setS.setAccessible(true);
            Map<String, Map<Long, LocalDateTime>> ends = new HashMap<>();
            Map<String, Map<Long, Long>> seqs = new HashMap<>();
            for (com.product.domain.entity.TaskAssignment a : r1.getAssignments()) {
                java.util.function.BiConsumer<String, Long> add = (type, id) -> {
                    if (id == null) return;
                    ends.computeIfAbsent(type, k -> new HashMap<>()).merge(id, a.getPlannedEnd(),
                            (x, y) -> x.isAfter(y) ? x : y);
                    seqs.computeIfAbsent(type, k -> new HashMap<>()).merge(id, 1L, Long::sum);
                };
                add.accept("MACHINE", a.getMachineId());
                add.accept("MOLD", a.getMoldId());
                add.accept("PERSON", a.getPersonId());
                add.accept("WORKSTATION", a.getWorkstationId());
            }
            for (Map.Entry<String, Map<Long, LocalDateTime>> en : ends.entrySet()) {
                for (Map.Entry<Long, LocalDateTime> e2 : en.getValue().entrySet()) {
                    setT.invoke(runtime, en.getKey(), e2.getKey(), e2.getValue());
                }
            }
            for (Map.Entry<String, Map<Long, Long>> en : seqs.entrySet()) {
                for (Map.Entry<Long, Long> e2 : en.getValue().entrySet()) {
                    setS.invoke(runtime, en.getKey(), e2.getKey(), e2.getValue() + 1);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 第一轮各任务 planned_end（依赖约束跨轮可见，monolith loadPlannedEndByTaskIds 语义）。 */
    static Map<Long, LocalDateTime> plannedEnds(TaskSchedulingCalculator.ScheduleBatchResult r1) {
        Map<Long, LocalDateTime> m = new HashMap<>();
        for (com.product.domain.entity.TaskAssignment a : r1.getAssignments()) {
            m.put(a.getTaskId(), a.getPlannedEnd());
        }
        return m;
    }

    /** resourceSequenceMap（TaskAssignment 上的各资源类型序号，持久化写 sequence 的来源）。 */
    static String seq(com.product.domain.entity.TaskAssignment a, String type) {
        Map<String, Long> map = a.getResourceSequenceMap();
        if (map == null) {
            return "null";
        }
        Long v = map.get(type);
        return v == null ? "null" : v.toString();
    }

    /** 构造真实单体 Calculator：注入真实 RouteRuleRegistry/ChangeoverCalculator（mapper 不参与纯计算路径）。 */
    static TaskSchedulingCalculator realCalculator() {
        TaskSchedulingCalculator c = new TaskSchedulingCalculator();
        try {
            java.lang.reflect.Field registry = TaskSchedulingCalculator.class.getDeclaredField("routeRuleRegistry");
            registry.setAccessible(true);
            registry.set(c, new RouteRuleRegistry(
                    List.of(new SetupMachineRule(), new InjectMachineRule(), new PostWorkstationRule()),
                    List.of(new SetupBaseTimeModel(), new PostUnitTimeModel(),
                            new InjectA2TimeModel(new InjectDurationCalculator()))));
            java.lang.reflect.Field chg = TaskSchedulingCalculator.class.getDeclaredField("changeoverCalculator");
            chg.setAccessible(true);
            chg.set(c, new ChangeoverCalculator());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return c;
    }

    /** newCtx：通过反射构造（无 Spring 环境），与单体 buildSchedulingResourceContext 相同字段。 */
    static TaskSchedulingQueryService.SchedulingResourceContext newCtx(List<Resource> resources,
            Map<String, List<Resource>> byType, Map<Long, Calendar> calendars,
            ChangeoverRule rule, Map<Long, Product> products) throws Exception {
        Constructor<TaskSchedulingQueryService.SchedulingResourceContext> ctor =
                TaskSchedulingQueryService.SchedulingResourceContext.class
                        .getDeclaredConstructor(List.class, Map.class, Map.class, ChangeoverRule.class, Map.class);
        return ctor.newInstance(resources, byType, calendars, rule, products);
    }
}
