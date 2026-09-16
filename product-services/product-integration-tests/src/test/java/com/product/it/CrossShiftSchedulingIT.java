package com.product.it;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static com.product.it.ItSupport.expect;
import static com.product.it.ItSupport.qScalar;
import static com.product.it.ItSupport.step;

/**
 * R3 跨班次排程套件（真实日历数据 + REST 排程动作 + planning_db 库级窗口断言）：
 *
 * <ol>
 *   <li>班次边界占用：720 分钟任务（= 12h 班次长度）恰好占满 [08:00,20:00]，结束于 20:00
 *       边界不被推日；10 分钟任务 19:50 开始于边界前收尾；</li>
 *   <li>跨班次推迟与次日承接：700 分钟任务 12:00 起算溢出 20:00 → 推迟到下一工作日
 *       08:00 开工（隔夜 break 间隙不占用）；SETUP→INJECT→POST 链跨班次整体承接；</li>
 *   <li>逗号分隔日历（真实数据形态）+ 非工作日跳过："Mon,Wed" 日历下周一 19:00 的
 *       120 分钟任务推迟到周三 08:00（周二被模式排除）。</li>
 * </ol>
 *
 * <p>日历/资源/任务经 SQL 种子（IT 专属段，与归档 probe 同形态），排程动作全部走 REST
 * （/pps/assignment/scheduleAllAsync），断言下探 planning_db.task_assignment 窗口。
 * 三轮排程串行（任务状态门控），保证窗口数学确定性。</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Order(3)
@Timeout(900)
class CrossShiftSchedulingIT {

    /** 全周逗号日历（Mon,Tue,…,Sun）。 */
    private static final long CAL_ALL_DAYS = 701;
    /** 逗号非连续日历（Mon,Wed）。 */
    private static final long CAL_MON_WED = 702;
    private static final long PRODUCT = 701, ROUTE = 701;
    private static final long MACHINE_A = 730, MACHINE_B = 731, MACHINE_MON_WED = 732;
    private static final long MOLD = 751, PERSON = 761, WORKSTATION = 771;
    private static final long CUSTOMER = 701, ORDER = 700_002, LINE_A = 700_012, LINE_B = 700_013;
    private static final long BATCH_BOUNDARY = 7_300_101, BATCH_DEFER = 7_300_102, BATCH_MON_WED = 7_300_103;
    private static final long T_BOUNDARY = 790_100_001L, T_DEFER = 790_100_002L;
    private static final long T_SETUP = 790_100_011L, T_INJECT = 790_100_012L, T_POST = 790_100_013L;
    private static final long T_MON_WED = 790_100_021L;
    private static final long TASK_LO = 790_100_001L, TASK_HI = 790_100_021L;

    @BeforeAll
    static void setup() {
        ItSupport.beginSuite("CrossShiftSchedulingIT");
        ItSupport.preflightOrDie();
        ItSupport.adminToken();
        ItSupport.cleanupAll();
    }

    @AfterAll
    static void teardown() {
        try {
            ItSupport.cleanupAll();
        } finally {
            ItSupport.endSuite("CrossShiftSchedulingIT");
        }
    }

    private static void seedAll() {
        Seed.changeoverRuleBaseline();
        // 逗号分隔日历（R3 真实数据形态；与 stub 的「Mon-Tue-…」连字符形态刻意区隔）
        Seed.calendar(CAL_ALL_DAYS, "IT跨班次全周班次", "Mon,Tue,Wed,Thu,Fri,Sat,Sun", "08:00:00", "20:00:00");
        Seed.calendar(CAL_MON_WED, "IT跨班次周一三班次", "Mon,Wed", "08:00:00", "20:00:00");
        Seed.machine(MACHINE_A, CAL_ALL_DAYS, "IT跨班次机台730", 120);
        Seed.machine(MACHINE_B, CAL_ALL_DAYS, "IT跨班次机台731", 200);
        Seed.machine(MACHINE_MON_WED, CAL_MON_WED, "IT跨班次机台732", 150);
        Seed.mold(MOLD, "IT-M751", 4);
        Seed.person(PERSON, CAL_ALL_DAYS, "IT跨班次操作员761");
        Seed.workstation(WORKSTATION, CAL_ALL_DAYS, "IT跨班次工位771");
        for (long m : List.of(MACHINE_A, MACHINE_B, MACHINE_MON_WED)) {
            Seed.compatibility(m, MOLD);
        }
        ItSupport.execSql("master_data_db", """
                INSERT INTO product (product_id, product_name, material_code, color_code, create_time)
                VALUES (%d, 'IT跨班次产品701', 'IT-P701', 'BLUE', NOW())
                ON DUPLICATE KEY UPDATE material_code=VALUES(material_code);
                INSERT INTO product_mold_param (product_id, mold_id, cycle_time_sec, cavity, yield_rate, utilization)
                VALUES (%d, %d, 30.00, 4, 0.9500, 0.9000)
                ON DUPLICATE KEY UPDATE cycle_time_sec=VALUES(cycle_time_sec);
                INSERT INTO product_route (route_id, product_id, version, is_active, create_time)
                VALUES (%d, %d, 'v1', 1, NOW()) ON DUPLICATE KEY UPDATE is_active=1;
                """.formatted(PRODUCT, PRODUCT, MOLD, ROUTE, PRODUCT));
        ItSupport.registerProduct(PRODUCT);
        Seed.capability(PERSON, "SETUP", PRODUCT);
        Seed.capability(PERSON, "INJECT", PRODUCT);
        Seed.capability(PERSON, "POST_QC_PUTAWAY", PRODUCT);
        Seed.capability(WORKSTATION, "POST_QC_PUTAWAY", PRODUCT);
        Seed.demandSeed(CUSTOMER, "IT跨班次客户701", ORDER, "2026-12-31 00:00:00", 6, LINE_A, PRODUCT, 100);
        Seed.demandSeed(CUSTOMER, "IT跨班次客户701", ORDER, "2026-12-31 00:00:00", 6, LINE_B, PRODUCT, 100);
    }

    @Test
    @Order(1)
    void shiftBoundaryAndOvernightDeferral() {
        step("== 轮 1：班次边界占用（720min 占满班次 / 700min 溢出跨班次） ==");
        seedAll();
        LocalDate d = LocalDate.now().plusDays(1); // 全周日历：任意日子均为工作日
        Seed.batch(BATCH_BOUNDARY, LINE_A, 20, List.of(
                new Seed.TaskSpec(T_BOUNDARY, "SETUP", 1, 720, d.atTime(8, 0), "RULE_SETUP_MACHINE", null)));
        Seed.batch(BATCH_DEFER, LINE_A, 20, List.of(
                new Seed.TaskSpec(T_DEFER, "INJECT", 1, 700, d.atTime(12, 0), "RULE_INJECT_MACHINE", null)));

        long jobId = Seed.scheduleAll(Seed.ts(d.atTime(8, 0)));
        Seed.awaitJobSuccess("轮 1 排程 SUCCESS（2 任务）", jobId, Duration.ofSeconds(120));

        String[] boundary = assignment(T_BOUNDARY);
        expect("边界任务 720min 占满班次：开始于 D 08:00", boundary[1].equals(Seed.ts(d.atTime(8, 0))), str(boundary));
        expect("边界任务恰好结束于班次边界 D 20:00（不推日）", boundary[2].equals(Seed.ts(d.atTime(20, 0))), str(boundary));
        String[] defer = assignment(T_DEFER);
        expect("700min 任务溢出 20:00 → 推迟到次日班首 08:00（隔夜 break 不占用）",
                defer[1].equals(Seed.ts(d.plusDays(1).atTime(8, 0))), str(defer));
        expect("次日承接后 08:00+700min=19:40 当班完成", defer[2].equals(Seed.ts(d.plusDays(1).atTime(19, 40))),
                str(defer));
    }

    @Test
    @Order(2)
    void multiTaskChainDefersAcrossShiftTogether() {
        step("== 轮 2：SETUP→INJECT→POST 链跨班次整体承接（工位分支） ==");
        LocalDate d = LocalDate.now().plusDays(1);
        finishRound();
        // 依赖约束绑定的是库内既有前序计划结束时间（同轮内按 earliest_start 定序，与 probe 同形）
        Seed.batch(BATCH_DEFER, LINE_A, 5, List.of(
                new Seed.TaskSpec(T_SETUP, "SETUP", 1, 10, d.atTime(19, 50), "RULE_SETUP_MACHINE", null),
                new Seed.TaskSpec(T_INJECT, "INJECT", 2, 60, d.plusDays(1).atTime(8, 0),
                        "RULE_INJECT_MACHINE", T_SETUP),
                new Seed.TaskSpec(T_POST, "POST_QC_PUTAWAY", 3, 30, d.plusDays(1).atTime(9, 0),
                        "RULE_POST_WORKSTATION", T_INJECT)));

        long jobId = Seed.scheduleAll(Seed.ts(d.atTime(8, 0)));
        Seed.awaitJobSuccess("轮 2 排程 SUCCESS（3 任务链）", jobId, Duration.ofSeconds(120));

        String[] setup = assignment(T_SETUP);
        expect("链首 SETUP 于 19:50 开工（班次尾部）", setup[1].equals(Seed.ts(d.atTime(19, 50))), str(setup));
        expect("链首 SETUP 恰好 20:00 收在边界", setup[2].equals(Seed.ts(d.atTime(20, 0))), str(setup));
        String[] inject = assignment(T_INJECT);
        expect("依赖任务 INJECT 跨班次推迟至次日 08:00 开工",
                inject[1].equals(Seed.ts(d.plusDays(1).atTime(8, 0))), str(inject));
        expect("INJECT 60min 次日 09:00 完成", inject[2].equals(Seed.ts(d.plusDays(1).atTime(9, 0))), str(inject));
        String[] post = assignment(T_POST);
        expect("工位分支 POST 紧随 INJECT 于次日 09:00 开工",
                post[1].equals(Seed.ts(d.plusDays(1).atTime(9, 0))), str(post));
        expect("POST 30min 次日 09:30 完成", post[2].equals(Seed.ts(d.plusDays(1).atTime(9, 30))), str(post));
        String ws = qScalar("planning_db", """
                SELECT COUNT(*) FROM task_assignment_resource
                WHERE task_id=%d AND resource_type='WORKSTATION' AND resource_id=%d"""
                .formatted(T_POST, WORKSTATION));
        expect("工位需求落派工资源行（WORKSTATION 分支库级证据）", "1".equals(ws), ws);
    }

    @Test
    @Order(3)
    void commaCalendarSkipsExcludedWorkday() {
        step("== 轮 3：逗号日历 Mon,Wed 排除周二（非工作日跳过） ==");
        LocalDate monday = Seed.nextMondayAfterToday();
        LocalDate wednesday = monday.plusDays(2);
        finishRound();
        // 独占机台池：全周机台置 OFFSHIFT，迫使任务使用 Mon,Wed 日历机台 732（IT 专属段种子维护）
        ItSupport.execSql("planning_db", "UPDATE operation_task SET status='DONE' WHERE task_id BETWEEN "
                + TASK_LO + " AND " + TASK_HI);
        ItSupport.execSql("master_data_db", "UPDATE resource SET status='OFFSHIFT' WHERE resource_id IN ("
                + MACHINE_A + "," + MACHINE_B + ")");
        Seed.batch(BATCH_MON_WED, LINE_B, 5, List.of(
                // 指派机台 732（requirement.resource_id 强制约束，共享库中其他 AVAILABLE 机台不参与）
                new Seed.TaskSpec(T_MON_WED, "SETUP", 1, 120, monday.atTime(19, 0), "RULE_SETUP_MACHINE",
                        null, MACHINE_MON_WED)));

        long jobId = Seed.scheduleAll(Seed.ts(monday.atTime(8, 0)));
        Seed.awaitJobSuccess("轮 3 排程 SUCCESS（1 任务）", jobId, Duration.ofSeconds(120));

        String[] mw = assignment(T_MON_WED);
        expect("机台选中 Mon,Wed 日历机台 732", mw[0].equals(String.valueOf(MACHINE_MON_WED)), str(mw));
        expect("120min 任务周一 19:00 溢出 20:00 → 跳过周二（模式排除）推迟至周三 08:00",
                mw[1].equals(Seed.ts(wednesday.atTime(8, 0))), str(mw));
        expect("周三 10:00 完成（120min 单班次内）", mw[2].equals(Seed.ts(wednesday.atTime(10, 0))), str(mw));
    }

    // ---------------- 工具 ----------------

    /** 轮间过渡：清 IT 段派工 + 任务置 DONE（机器可用时间回归空闲，窗口数学确定）。 */
    private static void finishRound() {
        ItSupport.execSql("planning_db", """
                DELETE FROM task_assignment_resource WHERE task_id BETWEEN %d AND %d;
                DELETE FROM task_assignment WHERE task_id BETWEEN %d AND %d;
                UPDATE operation_task SET status='DONE' WHERE task_id BETWEEN %d AND %d AND status='SCHEDULED';
                """.formatted(TASK_LO, TASK_HI, TASK_LO, TASK_HI, TASK_LO, TASK_HI));
    }

    /** task_assignment 主行（machine_id, planned_start, planned_end；DATE_FORMAT 定长字符串）。 */
    private static String[] assignment(long taskId) {
        String row = qScalar("planning_db", """
                SELECT CONCAT(IFNULL(machine_id,-1),'|',
                  DATE_FORMAT(planned_start,'%%Y-%%m-%%d %%H:%%i:%%s'),'|',
                  DATE_FORMAT(planned_end,'%%Y-%%m-%%d %%H:%%i:%%s'))
                FROM task_assignment WHERE task_id=%d""".formatted(taskId));
        expect("任务 %d 存在派工主行".formatted(taskId), row != null && row.split("\\|").length == 3, row);
        return row.split("\\|");
    }

    private static String str(String[] a) {
        return "machine=" + a[0] + " start=" + a[1] + " end=" + a[2];
    }
}
