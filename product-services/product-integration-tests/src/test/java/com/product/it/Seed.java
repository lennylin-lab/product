package com.product.it;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 种子助手：IT 专属段的排程负载 SQL 组装 + 常用 REST 动作封装。
 * 形态与归档 probe（e2e_reschedule_probe.py MASTER_SEED/TASK_SEED/RESET_READY）逐字段同源。
 * 仅允许作用于 ItSupport 的 IT 专属 ID 段（700-799 / 700000+ / 7300000+ / 790000000+）。
 */
public final class Seed {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static String ts(LocalDateTime t) {
        return t.format(TS);
    }

    // ---------------- master_data_db ----------------

    /** 逗号分隔日历（R3 覆盖真实逗号形态；与 stub「Mon-Tue-…」quirk 区隔）。 */
    public static void calendar(long calendarId, String name, String workdayPattern,
                                String shiftStart, String shiftEnd) {
        ItSupport.execSql("master_data_db", """
                INSERT INTO calendar (calendar_id, calendar_name, workday_pattern, shift_start, shift_end, create_time)
                VALUES (%d, '%s', '%s', '%s', '%s', NOW())
                ON DUPLICATE KEY UPDATE calendar_name=VALUES(calendar_name), workday_pattern=VALUES(workday_pattern),
                  shift_start=VALUES(shift_start), shift_end=VALUES(shift_end);
                """.formatted(calendarId, name, workdayPattern, shiftStart, shiftEnd));
        ItSupport.registerCalendar(calendarId);
    }

    public static void machine(long id, long calendarId, String name, int tonnage) {
        ItSupport.execSql("master_data_db", """
                INSERT INTO resource (resource_id, resource_type, name, status, calendar_id, create_time)
                VALUES (%d, 'MACHINE', '%s', 'AVAILABLE', %d, NOW())
                ON DUPLICATE KEY UPDATE status='AVAILABLE', calendar_id=VALUES(calendar_id);
                INSERT INTO machine (machine_id, tonnage, default_setup_time_min) VALUES (%d, %d, 0)
                ON DUPLICATE KEY UPDATE tonnage=VALUES(tonnage), default_setup_time_min=0;
                """.formatted(id, name, calendarId, id, tonnage));
    }

    public static void mold(long id, String code, int cavity) {
        ItSupport.execSql("master_data_db", """
                INSERT INTO resource (resource_id, resource_type, name, status, create_time)
                VALUES (%d, 'MOLD', '%s', 'AVAILABLE', NOW())
                ON DUPLICATE KEY UPDATE status='AVAILABLE';
                INSERT INTO mold (mold_id, mold_code, cavity, mold_status) VALUES (%d, '%s', %d, 'AVAILABLE')
                ON DUPLICATE KEY UPDATE mold_status='AVAILABLE';
                """.formatted(id, code, id, code, cavity));
    }

    public static void person(long id, long calendarId, String name) {
        ItSupport.execSql("master_data_db", """
                INSERT INTO resource (resource_id, resource_type, name, status, calendar_id, create_time)
                VALUES (%d, 'PERSON', '%s', 'AVAILABLE', %d, NOW())
                ON DUPLICATE KEY UPDATE status='AVAILABLE', calendar_id=VALUES(calendar_id);
                """.formatted(id, name, calendarId));
    }

    public static void workstation(long id, long calendarId, String name) {
        ItSupport.execSql("master_data_db", """
                INSERT INTO resource (resource_id, resource_type, name, status, calendar_id, create_time)
                VALUES (%d, 'WORKSTATION', '%s', 'AVAILABLE', %d, NOW())
                ON DUPLICATE KEY UPDATE status='AVAILABLE', calendar_id=VALUES(calendar_id);
                """.formatted(id, name, calendarId));
    }

    public static void compatibility(long machineId, long moldId) {
        ItSupport.execSql("master_data_db", """
                INSERT INTO machine_mold_compatibility (machine_id, mold_id, is_compatible) VALUES (%d, %d, 1)
                ON DUPLICATE KEY UPDATE is_compatible=1;
                """.formatted(machineId, moldId));
    }

    /** 人员/工位技能（opCode × product）。 */
    public static void capability(long resourceId, String opCode, long productId) {
        ItSupport.execSql("master_data_db", """
                INSERT INTO resource_capability (resource_id, op_code, product_id, is_enabled, priority_weight)
                VALUES (%d, '%s', %d, 1, 1)
                ON DUPLICATE KEY UPDATE is_enabled=1;
                """.formatted(resourceId, opCode, productId));
    }

    /** 换模规则基线（与 probe 相同的固定值；rule_id=1 为共享基线，不入清理段）。 */
    public static void changeoverRuleBaseline() {
        ItSupport.execSql("master_data_db", """
                INSERT INTO changeover_rule (rule_id, same_mold_time_min, different_mold_time_min,
                  material_change_extra_min, color_change_extra_min, create_time)
                VALUES (1, 5, 40, 10, 10, NOW())
                ON DUPLICATE KEY UPDATE same_mold_time_min=5, different_mold_time_min=40,
                  material_change_extra_min=10, color_change_extra_min=10;
                """);
    }

    // ---------------- demand_db ----------------

    public static void demandSeed(long customerId, String customerName, long orderId,
                                  String dueDate, int priority, long lineId, long productId, int qty) {
        ItSupport.execSql("demand_db", """
                INSERT INTO customer (customer_id, customer_name, remark, create_time)
                VALUES (%d, '%s', 'IT集成测试固定数据', NOW())
                ON DUPLICATE KEY UPDATE customer_name=VALUES(customer_name);
                INSERT INTO customer_order (order_id, customer_id, due_date, priority, status, create_time)
                VALUES (%d, %d, '%s', %d, 'CONFIRMED', NOW())
                ON DUPLICATE KEY UPDATE due_date=VALUES(due_date), priority=VALUES(priority), status='CONFIRMED';
                INSERT INTO order_line (order_line_id, order_id, product_id, qty, allocated_qty, status)
                VALUES (%d, %d, %d, %d, 0, 'RELEASED')
                ON DUPLICATE KEY UPDATE qty=VALUES(qty), status='RELEASED';
                """.formatted(customerId, customerName, orderId, customerId, dueDate, priority,
                lineId, orderId, productId, qty));
        ItSupport.registerCustomer(customerId);
        ItSupport.registerOrder(orderId);
        ItSupport.registerLine(lineId);
    }

    // ---------------- planning_db ----------------

    public record TaskSpec(long taskId, String opCode, int sequence, long durationMin,
                           LocalDateTime earliestStart, String rule, Long preTaskId, Long pinnedMachineId) {

        public TaskSpec(long taskId, String opCode, int sequence, long durationMin,
                        LocalDateTime earliestStart, String rule, Long preTaskId) {
            this(taskId, opCode, sequence, durationMin, earliestStart, rule, preTaskId, null);
        }
    }

    public static void batch(long batchId, long lineId, int qty, List<TaskSpec> tasks) {
        StringBuilder sb = new StringBuilder();
        long firstRequirementId = ItSupport.REQ_LO + (batchId % 1000) * 100;
        int reqSeq = 0;
        sb.append("DELETE FROM task_resource_requirement WHERE requirement_id BETWEEN ")
                .append(firstRequirementId).append(" AND ").append(firstRequirementId + 99).append(";\n");
        sb.append("DELETE FROM task_assignment_resource WHERE task_id IN (").append(taskIdList(tasks)).append(");\n");
        sb.append("DELETE FROM task_assignment WHERE task_id IN (").append(taskIdList(tasks)).append(");\n");
        sb.append("DELETE FROM task_resource_requirement WHERE task_id IN (").append(taskIdList(tasks)).append(");\n");
        sb.append("DELETE FROM task_dependency WHERE pre_task_id IN (").append(taskIdList(tasks))
                .append(") OR post_task_id IN (").append(taskIdList(tasks)).append(");\n");
        sb.append("DELETE FROM operation_task WHERE task_id IN (").append(taskIdList(tasks)).append(");\n");
        sb.append("DELETE FROM production_batch WHERE batch_id=").append(batchId).append(";\n");
        sb.append("INSERT INTO production_batch (batch_id, order_line_id, batch_qty, status, create_time) VALUES (")
                .append(batchId).append(", ").append(lineId).append(", ").append(qty)
                .append(", 'RELEASED', NOW());\n");
        List<String> taskRows = new ArrayList<>();
        List<String> depRows = new ArrayList<>();
        List<String> reqRows = new ArrayList<>();
        for (TaskSpec t : tasks) {
            taskRows.add("(%d, %d, '%s', %d, %d, '%s', 'READY', 'FIFO', '%s')".formatted(
                    t.taskId(), batchId, t.opCode(), t.sequence(), t.durationMin(),
                    ts(t.earliestStart()), t.rule()));
            if (t.preTaskId() != null) {
                depRows.add("(%d,%d)".formatted(t.preTaskId(), t.taskId()));
            }
            switch (t.opCode()) {
                case "SETUP" -> {
                    reqRows.add(req(firstRequirementId + reqSeq++, t.taskId(), "PERSON", t.opCode(), null));
                    reqRows.add(req(firstRequirementId + reqSeq++, t.taskId(), "MACHINE", t.opCode(),
                            t.pinnedMachineId()));
                }
                case "INJECT" -> {
                    reqRows.add(req(firstRequirementId + reqSeq++, t.taskId(), "PERSON", t.opCode(), null));
                    reqRows.add(req(firstRequirementId + reqSeq++, t.taskId(), "MACHINE", t.opCode(),
                            t.pinnedMachineId()));
                    reqRows.add(req(firstRequirementId + reqSeq++, t.taskId(), "MOLD", t.opCode(), null));
                }
                case "POST_QC_PUTAWAY" -> {
                    reqRows.add(req(firstRequirementId + reqSeq++, t.taskId(), "PERSON", t.opCode(), null));
                    reqRows.add(req(firstRequirementId + reqSeq++, t.taskId(), "WORKSTATION", t.opCode(), null));
                }
                default -> throw new IllegalArgumentException("未知工序: " + t.opCode());
            }
        }
        sb.append("INSERT INTO operation_task (task_id, batch_id, op_code, sequence, std_duration_min, ")
                .append("earliest_start, status, queue_policy, eligible_resource_rule) VALUES ")
                .append(String.join(",", taskRows)).append(";\n");
        if (!depRows.isEmpty()) {
            sb.append("INSERT INTO task_dependency (pre_task_id, post_task_id) VALUES ")
                    .append(String.join(",", depRows)).append(";\n");
        }
        sb.append("INSERT INTO task_resource_requirement (requirement_id, task_id, resource_type, resource_id, ")
                .append("capability_code, required_count, is_mandatory) VALUES ")
                .append(String.join(",", reqRows)).append(";\n");
        ItSupport.execSql("planning_db", sb.toString());
        ItSupport.registerBatch(batchId);
        for (TaskSpec t : tasks) {
            ItSupport.registerTask(t.taskId());
        }
    }

    private static String req(long requirementId, long taskId, String type, String opCode, Long pinnedResourceId) {
        String rid = pinnedResourceId == null ? "NULL" : String.valueOf(pinnedResourceId);
        return "(%d, %d, '%s', %s, '%s', 1, 1)".formatted(requirementId, taskId, type, rid, opCode);
    }

    private static String taskIdList(List<TaskSpec> tasks) {
        return tasks.stream().map(t -> String.valueOf(t.taskId()))
                .reduce((a, b) -> a + "," + b).orElse("");
    }

    /** probe 的 RESET_READY 等价物：清 IT 段派工 + 任务回 READY（仅限本套件段内任务）。 */
    public static void resetReady(long taskLo, long taskHi) {
        ItSupport.execSql("planning_db", """
                DELETE FROM task_assignment_resource WHERE task_id BETWEEN %d AND %d;
                DELETE FROM task_assignment WHERE task_id BETWEEN %d AND %d;
                UPDATE operation_task SET status='READY' WHERE task_id BETWEEN %d AND %d;
                """.formatted(taskLo, taskHi, taskLo, taskHi, taskLo, taskHi));
    }

    /** 查询某批次下已生成的工序任务（generateTask 之后，登记雪花 ID 用）。 */
    public static List<Long> taskIdsByBatch(long batchId) {
        String v = ItSupport.qScalar("planning_db",
                "SELECT GROUP_CONCAT(task_id) FROM operation_task WHERE batch_id=" + batchId);
        List<Long> ids = new ArrayList<>();
        if (v != null && !v.isBlank()) {
            for (String s : v.split(",")) {
                ids.add(Long.parseLong(s.trim()));
            }
        }
        return ids;
    }

    // ---------------- REST 动作 ----------------

    /** 资源状态事件登记（execution /internal 直连端口；网关 404 不可达）。 */
    public static void fireResourceEvent(long resourceId, String fromStatus, String toStatus, String reasonCode) {
        ItSupport.Resp r = ItSupport.direct(ItSupport.CFG.executionBase, "POST",
                "/internal/execution/resource-status-events",
                java.util.Map.of("resourceId", resourceId, "fromStatus", fromStatus,
                        "toStatus", toStatus, "reasonCode", reasonCode),
                ItSupport.adminToken());
        ItSupport.expect("资源状态事件登记 accepted（resourceId=%d %s→%s）".formatted(resourceId, fromStatus, toStatus),
                r.status() == 200 && r.code() == 200, r.raw());
    }

    /** 异步全量排程提交，返回 jobId。 */
    public static long scheduleAll(String assignmentStart) {
        ItSupport.Resp r = ItSupport.api("POST", "/pps/assignment/scheduleAllAsync",
                java.util.Map.of("assignmentStart", assignmentStart, "scheduleStrategy", "EARLIEST_START"));
        ItSupport.expect("POST /pps/assignment/scheduleAllAsync 受理", r.status() == 200 && r.code() == 200, r.raw());
        return Long.parseLong(r.dataAsText());
    }

    /** 等待指定 job 到达终态（默认 SUCCESS）。 */
    public static void awaitJobSuccess(String desc, long jobId, java.time.Duration timeout) {
        ItSupport.poll(desc, timeout, () -> {
            String row = ItSupport.qScalar("planning_db",
                    "SELECT CONCAT(status,':',total_task_count,':',IFNULL(error_message,'')) "
                            + "FROM schedule_job WHERE job_id=" + jobId);
            if (row == null) {
                return "job 不存在";
            }
            if (row.startsWith("FAILED")) {
                return "job FAILED: " + row;
            }
            return row.startsWith("SUCCESS") ? null : "status=" + row;
        });
    }

    /** 下一个工作日（按日历语义的简化版：供期望值计算）。 */
    public static LocalDate nextMondayAfterToday() {
        LocalDate d = LocalDate.now().plusDays(1);
        while (d.getDayOfWeek() != java.time.DayOfWeek.MONDAY) {
            d = d.plusDays(1);
        }
        return d;
    }

    private Seed() {
    }
}
