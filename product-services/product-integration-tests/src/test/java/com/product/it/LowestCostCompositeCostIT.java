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
import java.util.List;

import static com.product.it.ItSupport.expect;
import static com.product.it.ItSupport.qScalar;
import static com.product.it.ItSupport.step;

/**
 * R1 LOWEST_COST 综合成本套件（compose+REST 套件基建；2026-09-16 成本模型精度提升）：
 *
 * <p>候选池封闭机制：被测任务为 INJECT（MOLD 需求强制机台兼容裁决），仅本套件四台机台
 * 持有与本套件模具的兼容行——IT 段之外的共享库机台因无兼容行自动失格（不改写他人数据面）。</p>
 *
 * <ol>
 *   <li>换模历史驱动的选择反转：机台 743 携带换模派工历史（MachineLastAssignment 链），
 *       默认换型 0；机台 744 无历史、默认换型 20——切换前主键 setupCostMin 会选 743；
 *       默认权重（changeover-count-penalty=30）下 743 综合成本 0+30×1=30 &gt; 20，
 *       LOWEST_COST 反转为选择 744。时间计算零回归：窗口仍为 D 08:00-09:00。</li>
 *   <li>默认配置不回归：全部候选无历史、窗口不跨班 → compositeCost == setupCostMin，
 *       默认换型 10 的机台 745 胜出（743/744 已置 OFFSHIFT 收窄候选池），与切换前排序一致。</li>
 * </ol>
 *
 * <p>排程动作全部走 REST（/pps/assignment/scheduleAllAsync，scheduleStrategy=LOWEST_COST），
 * 断言下探 planning_db.task_assignment。两轮串行（任务状态门控 + 轮间清理派工行），
 * 保证窗口数学与选择确定性。种子/断言仅作用于 IT 专属 ID 段。</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Order(4)
@Timeout(900)
class LowestCostCompositeCostIT {

    /** 全周日历（08:00-20:00）。 */
    private static final long CAL_ALL_DAYS = 710;
    private static final long PRODUCT = 710, ROUTE = 710;
    private static final long M_HISTORY = 743, M_FRESH = 744, M_CHEAP = 745, M_PRICEY = 746;
    /** 被测任务的 INJECT 模具 + 历史派工引用的模具 ID（链上仅取 ID，759 无需实体行）。 */
    private static final long MOLD_INJECT = 758, MOLD_HISTORY = 759;
    private static final long PERSON = 763;
    private static final long CUSTOMER = 710, ORDER = 700_010, LINE = 700_020;
    private static final long BATCH_FLIP = 7_300_110, BATCH_DEFAULT = 7_300_111;
    private static final long T_FLIP = 790_110_001L, T_HIST = 790_110_011L, T_DEFAULT = 790_110_021L;

    @BeforeAll
    static void setup() {
        ItSupport.beginSuite("LowestCostCompositeCostIT");
        ItSupport.preflightOrDie();
        ItSupport.adminToken();
        ItSupport.cleanupAll();
    }

    @AfterAll
    static void teardown() {
        try {
            ItSupport.cleanupAll();
        } finally {
            ItSupport.endSuite("LowestCostCompositeCostIT");
        }
    }

    private static void seedAll() {
        Seed.changeoverRuleBaseline();
        Seed.calendar(CAL_ALL_DAYS, "IT综合成本全周班次", "Mon,Tue,Wed,Thu,Fri,Sat,Sun", "08:00:00", "20:00:00");
        Seed.machine(M_HISTORY, CAL_ALL_DAYS, "IT综合成本机台743", 120);
        Seed.machine(M_FRESH, CAL_ALL_DAYS, "IT综合成本机台744", 120);
        Seed.machine(M_CHEAP, CAL_ALL_DAYS, "IT综合成本机台745", 120);
        Seed.machine(M_PRICEY, CAL_ALL_DAYS, "IT综合成本机台746", 120);
        Seed.mold(MOLD_INJECT, "IT-M758", 4);
        // 机台默认换型时间（Seed.machine 固定写 0，按场景改写）：轮 1 反转由 743=0（有历史）
        // vs 744=20（无历史）+ 换模惩罚 30 驱动；745/746=90 不参与决胜
        ItSupport.execSql("master_data_db", """
                UPDATE machine SET default_setup_time_min=0 WHERE machine_id=%d;
                UPDATE machine SET default_setup_time_min=20 WHERE machine_id=%d;
                UPDATE machine SET default_setup_time_min=90 WHERE machine_id IN (%d,%d);
                """.formatted(M_HISTORY, M_FRESH, M_CHEAP, M_PRICEY));
        // 候选池封闭：仅本套件四台机台与 INJECT 模具兼容（INJECT 的 MOLD 需求强制裁决，
        // IT 段之外共享库机台无兼容行自动失格，无需改写其状态）
        for (long m : List.of(M_HISTORY, M_FRESH, M_CHEAP, M_PRICEY)) {
            Seed.compatibility(m, MOLD_INJECT);
        }
        Seed.person(PERSON, CAL_ALL_DAYS, "IT综合成本操作员763");
        ItSupport.execSql("master_data_db", """
                INSERT INTO product (product_id, product_name, material_code, color_code, create_time)
                VALUES (%d, 'IT综合成本产品710', 'IT-P710', 'RED', NOW())
                ON DUPLICATE KEY UPDATE material_code=VALUES(material_code);
                INSERT INTO product_mold_param (product_id, mold_id, cycle_time_sec, cavity, yield_rate, utilization)
                VALUES (%d, %d, 30.00, 4, 0.9500, 0.9000)
                ON DUPLICATE KEY UPDATE cycle_time_sec=VALUES(cycle_time_sec);
                INSERT INTO product_route (route_id, product_id, version, is_active, create_time)
                VALUES (%d, %d, 'v1', 1, NOW()) ON DUPLICATE KEY UPDATE is_active=1;
                """.formatted(PRODUCT, PRODUCT, MOLD_INJECT, ROUTE, PRODUCT));
        ItSupport.registerProduct(PRODUCT);
        Seed.capability(PERSON, "INJECT", PRODUCT);
        Seed.demandSeed(CUSTOMER, "IT综合成本客户710", ORDER, "2026-12-31 00:00:00", 6, LINE, PRODUCT, 20);

        // 共享库中 IT 段外 AVAILABLE 机台仅记录不处理（INJECT 兼容裁决已将其排除在候选池外）
        String outside = qScalar("master_data_db", """
                SELECT COUNT(*) FROM resource WHERE resource_type='MACHINE' AND status='AVAILABLE'
                AND (resource_id < 700 OR resource_id > 799)""".stripIndent());
        step("共享库 IT 段外 AVAILABLE 机台数=" + outside + "（无 758 兼容行，不参与候选）");
    }

    @Test
    @Order(1)
    void changeoverHistoryShouldFlipLowestCostSelection() {
        step("== 轮 1：换模历史驱动 LOWEST_COST 选择反转（743 默认 0+历史惩罚 30 vs 744 默认 20） ==");
        seedAll();
        LocalDate d = LocalDate.now().plusDays(1);
        Seed.batch(BATCH_FLIP, LINE, 20, List.of(
                // T_HIST 仅作历史负载（随后置 DONE + 手工派工行），T_FLIP 为被测任务
                new Seed.TaskSpec(T_HIST, "INJECT", 1, 60, d.minusDays(1).atTime(8, 0), "RULE_INJECT_MACHINE", null),
                new Seed.TaskSpec(T_FLIP, "INJECT", 2, 60, d.atTime(8, 0), "RULE_INJECT_MACHINE", null)));
        // 历史链：机台 743 最近一次派工 = T_HIST（含 MOLD 资源行），planned_end 在被测窗口之前
        ItSupport.execSql("planning_db", """
                INSERT INTO task_assignment (task_id, machine_id, planned_start, planned_end, sequence_on_resource, create_time)
                VALUES (%d, %d, '%s', '%s', 1, NOW());
                INSERT INTO task_assignment_resource (task_id, resource_id, resource_type, create_time)
                VALUES (%d, %d, 'MOLD', NOW());
                UPDATE operation_task SET status='DONE' WHERE task_id=%d;
                """.formatted(T_HIST, M_HISTORY,
                Seed.ts(d.minusDays(1).atTime(8, 0)), Seed.ts(d.minusDays(1).atTime(9, 0)),
                T_HIST, MOLD_HISTORY, T_HIST));

        long jobId = scheduleAllLowestCost(Seed.ts(d.atTime(8, 0)));
        Seed.awaitJobSuccess("轮 1 排程 SUCCESS（1 被测任务）", jobId, Duration.ofSeconds(120));

        String[] flip = assignment(T_FLIP);
        expect("换模历史反转选择：机台 743（0+30×1=30）被无历史机台 744（20）反超（切换前会选 743）",
                flip[0].equals(String.valueOf(M_FRESH)), str(flip));
        expect("窗口保持 D 08:00-09:00（成本只改机台选择，时间计算零变化）",
                flip[1].equals(Seed.ts(d.atTime(8, 0))) && flip[2].equals(Seed.ts(d.atTime(9, 0))), str(flip));
    }

    @Test
    @Order(2)
    void defaultWeightsShouldKeepSetupCostOrderingWhenNoHistory() {
        step("== 轮 2：默认配置不回归（无换模历史 → compositeCost == setupCostMin） ==");
        LocalDate d = LocalDate.now().plusDays(1);
        finishRound();
        // 轮 2 收窄候选池（743/744 置 OFFSHIFT，属本套件数据面）并拉开换型差异：745=10 / 746=45
        ItSupport.execSql("master_data_db", """
                UPDATE resource SET status='OFFSHIFT' WHERE resource_id IN (%d,%d);
                UPDATE machine SET default_setup_time_min=10 WHERE machine_id=%d;
                UPDATE machine SET default_setup_time_min=45 WHERE machine_id=%d;
                """.formatted(M_HISTORY, M_FRESH, M_CHEAP, M_PRICEY));
        Seed.batch(BATCH_DEFAULT, LINE, 20, List.of(
                new Seed.TaskSpec(T_DEFAULT, "INJECT", 1, 60, d.atTime(8, 0), "RULE_INJECT_MACHINE", null)));

        long jobId = scheduleAllLowestCost(Seed.ts(d.atTime(8, 0)));
        Seed.awaitJobSuccess("轮 2 排程 SUCCESS（1 任务）", jobId, Duration.ofSeconds(120));

        String[] plain = assignment(T_DEFAULT);
        expect("默认权重、无历史：默认换型 10 的机台 745 胜出（与切换前 setupCostMin 排序一致）",
                plain[0].equals(String.valueOf(M_CHEAP)), str(plain));
    }

    // ---------------- 工具 ----------------

    /** 轮间过渡：清 IT 段派工（含历史链）+ 任务置 DONE（机台历史与可用时间回归受控）。 */
    private static void finishRound() {
        ItSupport.execSql("planning_db", """
                DELETE FROM task_assignment_resource WHERE task_id BETWEEN %d AND %d;
                DELETE FROM task_assignment WHERE task_id BETWEEN %d AND %d;
                UPDATE operation_task SET status='DONE' WHERE task_id BETWEEN %d AND %d AND status='SCHEDULED';
                """.formatted(T_FLIP, T_DEFAULT, T_FLIP, T_DEFAULT, T_FLIP, T_DEFAULT));
    }

    /** LOWEST_COST 异步全量排程提交（Seed.scheduleAll 的策略变体），返回 jobId。 */
    private static long scheduleAllLowestCost(String assignmentStart) {
        ItSupport.Resp r = ItSupport.api("POST", "/pps/assignment/scheduleAllAsync",
                java.util.Map.of("assignmentStart", assignmentStart, "scheduleStrategy", "LOWEST_COST"));
        expect("POST /pps/assignment/scheduleAllAsync（LOWEST_COST）受理",
                r.status() == 200 && r.code() == 200, r.raw());
        return Long.parseLong(r.dataAsText());
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
