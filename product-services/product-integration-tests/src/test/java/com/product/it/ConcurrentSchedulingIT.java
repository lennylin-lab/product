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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.product.it.ItSupport.CFG;
import static com.product.it.ItSupport.expect;
import static com.product.it.ItSupport.poll;
import static com.product.it.ItSupport.qScalar;
import static com.product.it.ItSupport.step;

/**
 * R4 并发排程套件（真实 Redis 互斥；sweeper 节拍经 it-up.sh 注入
 * {@code PRODUCT_PPS_SCHEDULE_TIMEOUT_SCAN_DELAY_MS=1000} 缩短为 1s）：
 *
 * <ol>
 *   <li>N=8 并发 scheduleAllAsync：恰好 1 个受理成功、其余 7 个互斥拒绝
 *       （「当前已有排程任务在执行」），且只产生一行新 schedule_job 并 SUCCESS；</li>
 *   <li>pending 标记排空：标记存在期间 sweeper 不排空（有运行任务则保留），
 *       任务完成后 1s 节拍内补跑全量重排并比较清除标记；</li>
 *   <li>标记 compare-and-delete 竞态：陈旧标记被真实触发覆盖后由提交方自己的
 *       比较清除收敛；并发双触发（重复置标）不丢重排且无残留幻影标记。</li>
 * </ol>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Order(1)
@Timeout(900)
class ConcurrentSchedulingIT {

    private static final long CAL_ALL_DAYS = 703, PRODUCT = 702, ROUTE = 702;
    private static final long MACHINE_A = 740, MACHINE_B = 741;
    private static final long MOLD = 752, PERSON = 762, WORKSTATION = 772;
    private static final long CUSTOMER = 702, ORDER = 700_003, LINE = 700_014;
    /** 并发负载：30 批 × 3 工序 = 90 READY 任务。 */
    private static final int LOAD_BATCHES = 30;
    private static final long LOAD_BATCH_BASE = 7_300_501L;
    private static final long LOAD_TASK_BASE = 790_500_001L;
    private static final long TASK_LO = LOAD_TASK_BASE, TASK_HI = LOAD_TASK_BASE + LOAD_BATCHES * 3L - 1;
    private static final int CONCURRENCY = 8;

    @BeforeAll
    static void setup() {
        ItSupport.beginSuite("ConcurrentSchedulingIT");
        ItSupport.preflightOrDie();
        ItSupport.adminToken();
        ItSupport.cleanupAll();
    }

    @AfterAll
    static void teardown() {
        try {
            ItSupport.cleanupAll();
        } finally {
            ItSupport.endSuite("ConcurrentSchedulingIT");
        }
    }

    private static void seedLoad() {
        Seed.changeoverRuleBaseline();
        Seed.calendar(CAL_ALL_DAYS, "IT并发全周班次", "Mon,Tue,Wed,Thu,Fri,Sat,Sun", "08:00:00", "20:00:00");
        Seed.machine(MACHINE_A, CAL_ALL_DAYS, "IT并发机台740", 120);
        Seed.machine(MACHINE_B, CAL_ALL_DAYS, "IT并发机台741", 200);
        Seed.mold(MOLD, "IT-M752", 2);
        Seed.person(PERSON, CAL_ALL_DAYS, "IT并发操作员762");
        Seed.workstation(WORKSTATION, CAL_ALL_DAYS, "IT并发工位772");
        for (long m : List.of(MACHINE_A, MACHINE_B)) {
            Seed.compatibility(m, MOLD);
        }
        ItSupport.execSql("master_data_db", """
                INSERT INTO product (product_id, product_name, material_code, color_code, create_time)
                VALUES (%d, 'IT并发产品702', 'IT-P702', 'RED', NOW())
                ON DUPLICATE KEY UPDATE material_code=VALUES(material_code);
                INSERT INTO product_mold_param (product_id, mold_id, cycle_time_sec, cavity, yield_rate, utilization)
                VALUES (%d, %d, 30.00, 2, 0.9500, 0.9000)
                ON DUPLICATE KEY UPDATE cycle_time_sec=VALUES(cycle_time_sec);
                INSERT INTO product_route (route_id, product_id, version, is_active, create_time)
                VALUES (%d, %d, 'v1', 1, NOW()) ON DUPLICATE KEY UPDATE is_active=1;
                """.formatted(PRODUCT, PRODUCT, MOLD, ROUTE, PRODUCT));
        ItSupport.registerProduct(PRODUCT);
        Seed.capability(PERSON, "SETUP", PRODUCT);
        Seed.capability(PERSON, "INJECT", PRODUCT);
        Seed.capability(PERSON, "POST_QC_PUTAWAY", PRODUCT);
        Seed.capability(WORKSTATION, "POST_QC_PUTAWAY", PRODUCT);
        Seed.demandSeed(CUSTOMER, "IT并发客户702", ORDER, "2026-12-31 00:00:00", 5, LINE, PRODUCT, 900);
        LocalDateTime base = LocalDate.now().plusDays(1).atTime(8, 0);
        for (int i = 0; i < LOAD_BATCHES; i++) {
            long batchId = LOAD_BATCH_BASE + i;
            long t1 = LOAD_TASK_BASE + i * 3L;
            Seed.batch(batchId, LINE, 30, List.of(
                    new Seed.TaskSpec(t1, "SETUP", 1, 600, base, "RULE_SETUP_MACHINE", null),
                    new Seed.TaskSpec(t1 + 1, "INJECT", 2, 20, base, "RULE_INJECT_MACHINE", t1),
                    new Seed.TaskSpec(t1 + 2, "POST_QC_PUTAWAY", 3, 300, base, "RULE_POST_WORKSTATION", t1 + 1)));
        }
    }

    @Test
    @Order(1)
    void concurrentSubmitExactlyOneWins() {
        step("== 8 线程并发提交：恰好 1 受理 + 7 互斥拒绝 ==");
        seedLoad();
        long watermark = ItSupport.maxJobId();

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        List<Future<ItSupport.Resp>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < CONCURRENCY; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return ItSupport.api("POST", "/pps/assignment/scheduleAllAsync",
                            Map.of("assignmentStart", Seed.ts(LocalDate.now().plusDays(1).atTime(8, 0)),
                                    "scheduleStrategy", "EARLIEST_START"));
                }));
            }
            start.countDown();
            int success = 0;
            int mutexRejected = 0;
            long jobId = -1;
            for (Future<ItSupport.Resp> f : futures) {
                ItSupport.Resp r = f.get(60, TimeUnit.SECONDS);
                if (r.code() == 200 && r.dataAsText() != null) {
                    success++;
                    jobId = Long.parseLong(r.dataAsText());
                } else if (r.code() == 500 && r.msg().contains("当前已有排程任务在执行")) {
                    mutexRejected++;
                } else {
                    expect("并发提交响应均为受理或互斥拒绝", false, r.raw());
                }
            }
            expect("恰好 1 个并发提交受理成功", success == 1, "success=" + success);
            expect("其余 " + (CONCURRENCY - 1) + " 个提交互斥拒绝（真实 Redis 锁语义）",
                    mutexRejected == CONCURRENCY - 1, "rejected=" + mutexRejected);
            String jobCount = qScalar("planning_db",
                    "SELECT COUNT(*) FROM schedule_job WHERE job_id > " + watermark);
            expect("并发窗口内只产生一行新 schedule_job", "1".equals(jobCount), jobCount);
            final long fJobId = jobId;
            poll("获胜排程任务 SUCCESS（90 任务负载）", Duration.ofSeconds(180), () -> {
                String row = qScalar("planning_db",
                        "SELECT CONCAT(status,':',total_task_count,':',IFNULL(error_message,'')) "
                                + "FROM schedule_job WHERE job_id=" + fJobId);
                if (row == null) {
                    return "job 不存在";
                }
                if (row.startsWith("FAILED")) {
                    return "FAILED: " + row;
                }
                return row.startsWith("SUCCESS") ? null : "status=" + row;
            });
            expect("受理任务数 ≥ 90（IT 负载全部入排）", Long.parseLong(qScalar("planning_db",
                    "SELECT total_task_count FROM schedule_job WHERE job_id=" + fJobId)) >= LOAD_BATCHES * 3,
                    qScalar("planning_db", "SELECT total_task_count FROM schedule_job WHERE job_id=" + fJobId));
        } catch (Exception e) {
            throw new RuntimeException("并发提交测试执行失败: " + e.getMessage(), e);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @Order(2)
    void sweeperDrainsPendingMarkerWithShortenedCadence() {
        step("== pending 标记：运行任务期间保留，完成后 1s 节拍排空补跑 ==");
        Seed.resetReady(TASK_LO, TASK_HI);
        long watermark = ItSupport.maxJobId();

        // 先让负载 job 进入 RUNNING：RUNNING 会阻止 sweeper 排空，保证随后的置标-断言窗口
        // 无竞态（否则 1s 节拍 sweeper 会在空闲栈上合法排空刚置入的标记——CI 首跑踩坑）
        long jobId = Seed.scheduleAll(Seed.ts(LocalDate.now().plusDays(1).atTime(8, 0)));
        poll("负载任务进入 RUNNING", Duration.ofSeconds(60), () -> {
            String s = qScalar("planning_db", "SELECT status FROM schedule_job WHERE job_id=" + jobId);
            return "RUNNING".equals(s) ? null : "status=" + s;
        });

        // 标记在负载 RUNNING 期间置入（模拟更早的触发被互斥拒绝后留下的持久化标记）
        String markerValue = "it-drain-" + System.currentTimeMillis();
        ItSupport.redisSetTtl(CFG.rescheduleMarkerKey, markerValue, 600);
        expect("pending 标记置入（Redis db5 IT 栈）", markerValue.equals(ItSupport.redisGet(CFG.rescheduleMarkerKey)),
                ItSupport.redisGet(CFG.rescheduleMarkerKey));

        for (int i = 0; i < 3; i++) {
            String status = qScalar("planning_db", "SELECT status FROM schedule_job WHERE job_id=" + jobId);
            boolean active = "RUNNING".equals(status) || "PENDING".equals(status);
            expect("任务运行中标记保留且无补跑（sweeper 不排空活跃栈，第 " + (i + 1) + " 次观测）", !active
                    || (markerValueMatches() && Long.parseLong(qScalar("planning_db",
                    "SELECT COUNT(*) FROM schedule_job WHERE job_id > " + watermark + " AND job_id <> " + jobId)) == 0),
                    "status=" + status + " marker=" + ItSupport.redisGet(CFG.rescheduleMarkerKey));
            try {
                TimeUnit.SECONDS.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        poll("负载任务 SUCCESS", Duration.ofSeconds(180), () -> {
            String s = qScalar("planning_db", "SELECT status FROM schedule_job WHERE job_id=" + jobId);
            if (s == null) {
                return "job 不存在";
            }
            if (s.startsWith("FAILED")) {
                return "FAILED";
            }
            return s.startsWith("SUCCESS") ? null : "status=" + s;
        });

        final long wm = watermark;
        poll("完成后 1s 节拍 sweeper 排空标记（比较清除）", Duration.ofSeconds(25), () ->
                ItSupport.redisGet(CFG.rescheduleMarkerKey) == null ? null
                        : "marker=" + ItSupport.redisGet(CFG.rescheduleMarkerKey));
        poll("排空补跑全量重排 SUCCESS（恰好一行新任务）", Duration.ofSeconds(25), () -> {
            String count = qScalar("planning_db",
                    "SELECT COUNT(*) FROM schedule_job WHERE job_id > " + wm + " AND job_id <> " + jobId);
            String successRow = qScalar("planning_db",
                    "SELECT COUNT(*) FROM schedule_job WHERE job_id > " + wm
                            + " AND job_id <> " + jobId + " AND status='SUCCESS'");
            return "1".equals(count) && "1".equals(successRow) ? null
                    : "新任务数=" + count + " SUCCESS数=" + successRow;
        });
    }

    @Test
    @Order(3)
    void markerCompareAndDeleteRaceConverges() {
        step("== 标记 compare-and-delete：陈旧标记被覆盖；并发双触发不丢重排 ==");
        Seed.machine(MACHINE_A, CAL_ALL_DAYS, "IT并发机台740", 120); // 独立运行自足种子
        Seed.machine(MACHINE_B, CAL_ALL_DAYS, "IT并发机台741", 200);

        // (a) 陈旧标记 + 真实触发：触发方 markPending 覆盖陈旧值，提交成功后按自身值比较清除
        ItSupport.redisSetTtl(CFG.rescheduleMarkerKey, "it-stale-old", 600);
        long watermark = ItSupport.maxJobId();
        long versionBefore = masterVersion();
        Seed.fireResourceEvent(MACHINE_B, "AVAILABLE", "DOWN", "IT_RACE_DOWN");
        poll("master_data_db 741 回写 DOWN（回写收敛）", Duration.ofSeconds(20), () ->
                "DOWN".equals(qScalar("master_data_db",
                        "SELECT status FROM resource WHERE resource_id=" + MACHINE_B)) ? null
                        : "当前=" + qScalar("master_data_db",
                        "SELECT status FROM resource WHERE resource_id=" + MACHINE_B));
        poll("DOWN 触发受理 + 陈旧标记被覆盖清除", Duration.ofSeconds(40), () -> {
            String marker = ItSupport.redisGet(CFG.rescheduleMarkerKey);
            String newJob = qScalar("planning_db", "SELECT COUNT(*) FROM schedule_job WHERE job_id > "
                    + watermark + " AND status='SUCCESS'");
            return marker == null && Long.parseLong(newJob) >= 1 ? null
                    : "marker=" + marker + " 新SUCCESS任务=" + newJob;
        });
        expect("DOWN 回写版本 bump +1", masterVersion() == versionBefore + 1, masterVersion());

        // 恢复 741（AVAILABLE 触发集，等待补跑完成保持静默）
        Seed.fireResourceEvent(MACHINE_B, "DOWN", "AVAILABLE", "IT_RACE_RECOVER");
        ItSupport.waitForNewSuccessJob("741 恢复触发重排 SUCCESS", watermark, Duration.ofSeconds(60));
        poll("741 恢复 AVAILABLE（回写收敛）", Duration.ofSeconds(20), () ->
                "AVAILABLE".equals(qScalar("master_data_db",
                        "SELECT status FROM resource WHERE resource_id=" + MACHINE_B)) ? null
                        : "当前=" + qScalar("master_data_db",
                        "SELECT status FROM resource WHERE resource_id=" + MACHINE_B));

        // (b) 并发双触发（重复置标）：不丢重排、最终无幻影标记
        long watermark2 = ItSupport.maxJobId();
        long versionBeforeDual = masterVersion();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> fires = List.of(
                    pool.submit(() -> {
                        await(start);
                        Seed.fireResourceEvent(MACHINE_A, "AVAILABLE", "DOWN", "IT_RACE_DUAL_A");
                        return null;
                    }),
                    pool.submit(() -> {
                        await(start);
                        Seed.fireResourceEvent(MACHINE_B, "AVAILABLE", "DOWN", "IT_RACE_DUAL_B");
                        return null;
                    }));
            start.countDown();
            for (Future<?> f : fires) {
                f.get(60, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            throw new RuntimeException("双触发执行失败: " + e.getMessage(), e);
        } finally {
            pool.shutdownNow();
        }
        poll("并发双触发收敛：标记清除且 ≥1 次重排成功", Duration.ofSeconds(40), () -> {
            String marker = ItSupport.redisGet(CFG.rescheduleMarkerKey);
            String newJobs = qScalar("planning_db", "SELECT COUNT(*) FROM schedule_job WHERE job_id > "
                    + watermark2 + " AND status='SUCCESS'");
            return marker == null && Long.parseLong(newJobs) >= 1 ? null
                    : "marker=" + marker + " 新SUCCESS任务=" + newJobs;
        });
        expect("双触发两笔回写各自 bump（版本 +2）", masterVersion() == versionBeforeDual + 2, masterVersion());
        try {
            TimeUnit.SECONDS.sleep(3);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        expect("静默期后无幻影标记残留（sweeper 比较清除收敛）",
                ItSupport.redisGet(CFG.rescheduleMarkerKey) == null, ItSupport.redisGet(CFG.rescheduleMarkerKey));

        // 恢复双机台（事件驱动；各自等待重排完成）
        restore(MACHINE_A, watermark2);
        restore(MACHINE_B, ItSupport.maxJobId());
    }

    private static void restore(long machineId, long watermark) {
        Seed.fireResourceEvent(machineId, "DOWN", "AVAILABLE", "IT_RACE_RESTORE");
        poll("机台 " + machineId + " 恢复 AVAILABLE（回写收敛）", Duration.ofSeconds(20), () ->
                "AVAILABLE".equals(qScalar("master_data_db",
                        "SELECT status FROM resource WHERE resource_id=" + machineId)) ? null
                        : "当前=" + qScalar("master_data_db",
                        "SELECT status FROM resource WHERE resource_id=" + machineId));
        ItSupport.waitForNewSuccessJob("机台 " + machineId + " 恢复触发重排 SUCCESS", watermark, Duration.ofSeconds(60));
    }

    private static boolean markerValueMatches() {
        String v = ItSupport.redisGet(CFG.rescheduleMarkerKey);
        return v != null && v.startsWith("it-drain-");
    }

    private static long masterVersion() {
        // 全新库计数器表可能尚无行（首条 bump 才 INSERT）：无行按 0 起算
        String version = qScalar("master_data_db",
                "SELECT data_version FROM master_data_data_version WHERE scope='MASTER_DATA'");
        return version == null ? 0L : Long.parseLong(version);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
