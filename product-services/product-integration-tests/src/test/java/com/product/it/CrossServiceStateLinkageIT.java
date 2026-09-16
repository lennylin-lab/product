package com.product.it;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.product.it.ItSupport.CFG;
import static com.product.it.ItSupport.api;
import static com.product.it.ItSupport.expect;
import static com.product.it.ItSupport.poll;
import static com.product.it.ItSupport.qScalar;
import static com.product.it.ItSupport.step;

/**
 * R2 跨服务状态联动套件（compose + REST 黑盒；断言下探各服务库，每服务库至少一次库级证据）：
 *
 * <ol>
 *   <li>异常链：API 全链建客户/订单/订单行 → 拆批 → generateTask → 排程 →
 *       {@code POST /execute/event/start} → planning RUNNING + 批次 IN_PROCESS +
 *       demand 订单行/订单 IN_PRODUCTION（事件跨三服务收敛）→
 *       {@code POST /execute/event/exception}（reasonCode）→ 任务 PAUSED（批次/订单行级联与
 *       PAUSE 同款目标态）+ execution_db.task_event EXCEPTION 行含 reason_code +
 *       planning_db.consumed_event / demand_db.consumed_event 各 +1；</li>
 *   <li>资源链：DOWN 事件 → master_data_db 回写 status=DOWN + 版本 bump → 无人调排程接口
 *       自动全量重排 SUCCESS 且 DOWN 资源分配行为 0 → AVAILABLE 恢复回归（排回 + 版本再 bump）；</li>
 *   <li>MAINTENANCE 只回写不重排（版本 bump、无新 schedule_job、无 pending 标记）；</li>
 *   <li>非法 toStatus（BROKEN）→ 回写失败 fail-closed：consumed_event 不增、
 *       dead_letter_audit +1（重试耗尽进 DLX）、资源状态与版本不变。</li>
 * </ol>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Order(2)
@Timeout(900)
class CrossServiceStateLinkageIT {

    private static final long CALENDAR = 700, PRODUCT = 700, ROUTE = 700;
    private static final long MACHINE_DOWN = 720, MACHINE_PEER = 721, MACHINE_BROKEN = 722, MACHINE_MAINT = 723;
    private static final long MOLD = 750, PERSON = 760, WORKSTATION = 770;
    private static final long CUSTOMER = 700, ORDER = 700_001, LINE = 700_011;
    private static final long BATCH_DOWN1 = 7_300_001, BATCH_DOWN2 = 7_300_002;
    /** 资源链任务段（仅本套件的 6 个 SQL 种子任务）。 */
    private static final long TASK_LO = 790_000_011L, TASK_HI = 790_000_016L;

    private static volatile long productId;
    private static volatile long apiBatchId;

    @BeforeAll
    static void setup() {
        ItSupport.beginSuite("CrossServiceStateLinkageIT");
        ItSupport.preflightOrDie();
        ItSupport.adminToken();
        ItSupport.cleanupAll();
    }

    @AfterAll
    static void teardown() {
        try {
            ItSupport.cleanupAll();
        } finally {
            ItSupport.endSuite("CrossServiceStateLinkageIT");
        }
    }

    // ---------------- master/demand 种子（SQL 固定段 + API 产品写入路径） ----------------

    private static void seedMasterData() {
        Seed.changeoverRuleBaseline();
        Seed.calendar(CALENDAR, "IT联动常白班", "Mon,Tue,Wed,Thu,Fri,Sat,Sun", "08:00:00", "20:00:00");
        Seed.machine(MACHINE_DOWN, CALENDAR, "IT联动机台720", 120);
        Seed.machine(MACHINE_PEER, CALENDAR, "IT联动机台721", 200);
        Seed.machine(MACHINE_BROKEN, CALENDAR, "IT联动机台722", 150);
        Seed.machine(MACHINE_MAINT, CALENDAR, "IT联动机台723", 180);
        Seed.mold(MOLD, "IT-M750", 2);
        Seed.person(PERSON, CALENDAR, "IT联动操作员760");
        Seed.workstation(WORKSTATION, CALENDAR, "IT联动工位770");
        for (long m : List.of(MACHINE_DOWN, MACHINE_PEER, MACHINE_BROKEN, MACHINE_MAINT)) {
            Seed.compatibility(m, MOLD);
        }
    }

    /** 产品走 API 写入路径（POST /demand/product：demand → master-data 契约链）。 */
    private static void seedProductViaApi() {
        // activeRoute.productId 需显式 null（Map.of 不允许 null 值）
        java.util.Map<String, Object> activeRoute = new java.util.LinkedHashMap<>();
        activeRoute.put("productId", null);
        activeRoute.put("version", "v1");
        activeRoute.put("isActive", 1);
        activeRoute.put("operations", List.of(
                Map.of("opCode", "SETUP", "sequence", 1, "eligibleResourceRule",
                        "RULE_SETUP_MACHINE", "stdTimeModel", "TM_SETUP_BASE", "queuePolicy", "FIFO"),
                Map.of("opCode", "INJECT", "sequence", 2, "eligibleResourceRule",
                        "RULE_INJECT_MACHINE", "stdTimeModel", "TM_INJECT_A2", "queuePolicy", "FIFO"),
                Map.of("opCode", "POST_QC_PUTAWAY", "sequence", 3, "eligibleResourceRule",
                        "RULE_POST_WORKSTATION", "stdTimeModel", "TM_POST_UNIT", "queuePolicy", "FIFO")));
        ItSupport.Resp r = api("POST", "/demand/product", Map.of(
                "productName", "IT联动产品700", "materialCode", "IT-P700", "colorCode", "RED",
                "moldParams", List.of(Map.of("moldId", MOLD, "cycleTimeSec", 30, "cavity", 2,
                        "yieldRate", 0.95, "utilization", 0.9)),
                "activeRoute", activeRoute));
        expect("POST /demand/product 建产品（模具参数 + 3 工序启用路线）", r.status() == 200 && r.code() == 200, r.raw());
        ItSupport.Resp list = api("GET", "/demand/product/list?pageNum=1&pageSize=10&productName=IT联动产品700");
        JsonNode rows = list.body() == null ? null : list.body().get("rows");
        expect("GET /demand/product/list 可查到 IT 产品", list.status() == 200 && rows != null && !rows.isEmpty(),
                list.raw());
        // 重跑幂等：雪花 ID 单调，取最新一行
        long maxProductId = 0;
        for (JsonNode row : rows) {
            maxProductId = Math.max(maxProductId, row.get("productId").asLong());
        }
        productId = maxProductId;
        ItSupport.registerProduct(productId);
        expect("产品 id 取自 API 响应", productId > 0, productId);
        Seed.capability(PERSON, "SETUP", productId);
        Seed.capability(PERSON, "INJECT", productId);
        Seed.capability(PERSON, "POST_QC_PUTAWAY", productId);
        Seed.capability(WORKSTATION, "POST_QC_PUTAWAY", productId);
    }

    @Test
    @Order(1)
    void exceptionCascadeAcrossThreeServices() {
        step("== 异常链：start → 三域联动；exception → PAUSED 级联 ==");
        seedMasterData();
        seedProductViaApi();

        // 客户/订单/订单行全走 API（跨域校验链）
        api("POST", "/demand/customer", Map.of("customerName", "IT联动客户700", "remark", "跨服务联动"));
        ItSupport.Resp list = api("GET", "/demand/customer/list?pageNum=1&pageSize=10&customerName=IT联动客户700");
        long apiCustomerId = list.body().get("rows").get(0).get("customerId").asLong();
        ItSupport.registerCustomer(apiCustomerId);
        api("POST", "/demand/order", Map.of("customerId", apiCustomerId,
                "dueDate", "2026-12-31 00:00:00", "priority", 5));
        ItSupport.Resp orders = api("GET", "/demand/order/list?pageNum=1&pageSize=50");
        long apiOrderId = 0;
        for (JsonNode row : orders.body().get("rows")) {
            if (row.get("customerId").asLong() == apiCustomerId) {
                apiOrderId = Math.max(apiOrderId, row.get("orderId").asLong());
            }
        }
        ItSupport.registerOrder(apiOrderId);
        expect("API 建订单成功", apiOrderId > 0, apiOrderId);
        api("POST", "/demand/orderLine", Map.of("orderId", apiOrderId, "productId", productId, "qty", 50));
        ItSupport.Resp lines = api("GET", "/demand/orderLine/list?pageNum=1&pageSize=50&orderId=" + apiOrderId);
        long apiLineId = 0;
        for (JsonNode row : lines.body().get("rows")) {
            apiLineId = Math.max(apiLineId, row.get("orderLineId").asLong());
        }
        ItSupport.registerLine(apiLineId);
        expect("API 建订单行成功（产品跨域校验通过）", apiLineId > 0, apiLineId);

        expect("PUT /demand/order/check 确认", api("PUT", "/demand/order/check/" + apiOrderId).code() == 200);
        expect("PUT /demand/orderLine/release 释放", api("PUT", "/demand/orderLine/release/" + apiLineId).code() == 200);
        final long fOrderId = apiOrderId;
        final long fLineId = apiLineId;

        // 拆批 → 生成任务
        expect("POST /pps/batch 拆批", api("POST", "/pps/batch",
                Map.of("orderLineId", apiLineId, "batchQty", 30)).code() == 200);
        ItSupport.Resp batches = api("GET", "/pps/batch/list?pageNum=1&pageSize=10&orderId=" + apiOrderId);
        apiBatchId = batches.body().get("rows").get(0).get("batchId").asLong();
        ItSupport.registerBatch(apiBatchId);
        expect("PUT /pps/batch/release 释放批次", api("PUT", "/pps/batch/release/" + apiBatchId).code() == 200);
        expect("POST /pps/batch/generateTask 生成工序任务", api("POST", "/pps/batch/generateTask",
                List.of(apiBatchId)).code() == 200);
        List<Long> taskIds = Seed.taskIdsByBatch(apiBatchId);
        ItSupport.registerTasks(taskIds);
        expect("generateTask 生成 3 道工序任务", taskIds.size() == 3, taskIds);
        long setupTask = taskIds.stream().min(Long::compare).orElse(0L);

        // 基线排程（轮询 schedule_job 终态）
        long wm = ItSupport.maxJobId();
        String start = Seed.ts(LocalDate.now().plusDays(1).atTime(8, 0));
        long jobId = Seed.scheduleAll(start);
        Seed.awaitJobSuccess("基线排程 job SUCCESS", jobId, Duration.ofSeconds(120));
        expect("排程任务行数水位推进", jobId > wm, jobId + ">" + wm);
        expect("3 任务排程后 SCHEDULED", "3".equals(qScalar("planning_db",
                "SELECT COUNT(*) FROM operation_task WHERE batch_id=" + apiBatchId + " AND status='SCHEDULED'")),
                qScalar("planning_db", "SELECT status FROM operation_task WHERE batch_id=" + apiBatchId));

        // start：任务 RUNNING（planning 库级）+ 批次 IN_PROCESS（planning）+ 订单行/订单 IN_PRODUCTION（demand 库级）
        long consumedTaskBefore = consumedTaskEvents(setupTask);
        long consumedBatchBefore = consumedBatchEvents(apiBatchId);
        expect("POST /execute/event/start 受理", api("POST", "/execute/event/start/" + setupTask).code() == 200);
        final long fSetup = setupTask;
        poll("planning_db 任务 RUNNING（事件消费落库）", Duration.ofSeconds(30), () ->
                "RUNNING".equals(qScalar("planning_db",
                        "SELECT status FROM operation_task WHERE task_id=" + fSetup)) ? null
                        : "status=" + qScalar("planning_db",
                        "SELECT status FROM operation_task WHERE task_id=" + fSetup));
        poll("demand_db 订单行 IN_PRODUCTION + 订单 IN_PRODUCTION（级联推进）", Duration.ofSeconds(30), () -> {
            String line = qScalar("demand_db", "SELECT status FROM order_line WHERE order_line_id=" + fLineId);
            String order = qScalar("demand_db", "SELECT status FROM customer_order WHERE order_id=" + fOrderId);
            return "IN_PRODUCTION".equals(line) && "IN_PRODUCTION".equals(order) ? null : "line=" + line + " order=" + order;
        });
        expect("planning_db 批次 IN_PROCESS（批次重算级联）", "IN_PROCESS".equals(qScalar("planning_db",
                "SELECT status FROM production_batch WHERE batch_id=" + apiBatchId)),
                qScalar("planning_db", "SELECT status FROM production_batch WHERE batch_id=" + apiBatchId));
        expect("demand_db.planning_batch_state 投影 IN_PROCESS（第四处库级证据）",
                "IN_PROCESS".equals(qScalar("demand_db",
                        "SELECT status FROM planning_batch_state WHERE batch_id=" + apiBatchId)),
                qScalar("demand_db", "SELECT status FROM planning_batch_state WHERE batch_id=" + apiBatchId));
        expect("planning_db.consumed_event 记录 task.status.changed（APPLIED 才有流水）",
                consumedTaskEvents(setupTask) == consumedTaskBefore + 1, consumedTaskEvents(setupTask));
        expect("demand_db.consumed_event 记录 batch.progress.changed",
                consumedBatchEvents(apiBatchId) == consumedBatchBefore + 1, consumedBatchEvents(apiBatchId));

        // exception：任务 PAUSED（复用 PAUSED 目标态）+ reason_code 留痕 + 级联一致性
        long consumedTaskBeforeEx = consumedTaskEvents(setupTask);
        ItSupport.Resp ex = api("POST", "/execute/event/exception/" + setupTask,
                Map.of("reasonCode", "IT_QUALITY_HOLD", "remark", "IT 异常链联动验证"));
        expect("POST /execute/event/exception（reasonCode 必填契约）受理", ex.status() == 200 && ex.code() == 200, ex.raw());
        poll("planning_db 任务 PAUSED（异常事件消费）", Duration.ofSeconds(30), () ->
                "PAUSED".equals(qScalar("planning_db",
                        "SELECT status FROM operation_task WHERE task_id=" + fSetup)) ? null
                        : "status=" + qScalar("planning_db",
                        "SELECT status FROM operation_task WHERE task_id=" + fSetup));
        expect("批次保持 IN_PROCESS（PAUSED 与 PAUSE 同款批次级联）", "IN_PROCESS".equals(qScalar("planning_db",
                "SELECT status FROM production_batch WHERE batch_id=" + apiBatchId)),
                qScalar("planning_db", "SELECT status FROM production_batch WHERE batch_id=" + apiBatchId));
        expect("订单行保持 IN_PRODUCTION（PAUSED 不回退需求域状态）", "IN_PRODUCTION".equals(qScalar("demand_db",
                "SELECT status FROM order_line WHERE order_line_id=" + fLineId)),
                qScalar("demand_db", "SELECT status FROM order_line WHERE order_line_id=" + fLineId));
        String eventRow = qScalar("execution_db", "SELECT CONCAT(event_type,':',IFNULL(reason_code,''),':',remark) "
                + "FROM task_event WHERE task_id=" + fSetup + " AND event_type='EXCEPTION' ORDER BY event_id DESC LIMIT 1");
        expect("execution_db.task_event EXCEPTION 行含 reason_code",
                eventRow != null && eventRow.startsWith("EXCEPTION:IT_QUALITY_HOLD"), eventRow);
        expect("planning_db.consumed_event 异常事件流水 +1",
                consumedTaskEvents(setupTask) == consumedTaskBeforeEx + 1, consumedTaskEvents(setupTask));

        // 可观测性抽查：业务响应携带 X-Trace-Id（RequestContextFilter 合成）
        ItSupport.Resp traced = api("GET", "/pps/task/list?pageNum=1&pageSize=5&batchId=" + apiBatchId);
        expect("业务响应携带 X-Trace-Id（轨迹基线抽查）",
                traced.status() == 200 && traced.traceId() != null && !traced.traceId().isBlank(), traced.traceId());
    }

    // ---------------- 资源链：DOWN/恢复自动重排、MAINTENANCE 不触发、非法状态 fail-closed ----------------

    @Test
    @Order(2)
    void resourceDownTriggersRescheduleAndExcludesResource() {
        step("== 资源链：DOWN → 回写+版本bump+自动重排排除；AVAILABLE → 回归 ==");
        seedDemandAndTasks(true);

        long wm = ItSupport.maxJobId();
        String start = Seed.ts(LocalDate.now().plusDays(1).atTime(9, 0));
        long baselineJob = Seed.scheduleAll(start);
        Seed.awaitJobSuccess("资源链基线排程 SUCCESS（6 任务）", baselineJob, Duration.ofSeconds(120));
        String baselineMachines = qScalar("planning_db", """
                SELECT GROUP_CONCAT(DISTINCT machine_id) FROM task_assignment
                WHERE task_id BETWEEN %d AND %d""".formatted(TASK_LO, TASK_HI));
        expect("基线排程同时选用机台 720 与 721（两个 SETUP 各自指派约束）",
                baselineMachines != null && baselineMachines.contains("720") && baselineMachines.contains("721"),
                baselineMachines);

        long versionBefore = masterVersion();
        long consumedBefore = consumedResourceEvents(MACHINE_DOWN);
        Seed.resetReady(TASK_LO, TASK_HI);
        unpinMachineRequirements();
        Seed.fireResourceEvent(MACHINE_DOWN, "AVAILABLE", "DOWN", "IT_EQUIP_FAULT");

        expect("master_data_db resource 720 状态回写 DOWN", pollScalarEquals(
                "master_data_db resource 720 状态回写 DOWN", "master_data_db",
                "SELECT status FROM resource WHERE resource_id=" + MACHINE_DOWN, "DOWN", Duration.ofSeconds(20)));
        poll("master_data_db 版本幂等 bump +1（回写同事务）", Duration.ofSeconds(20), () ->
                masterVersion() == versionBefore + 1 ? null : "version=" + masterVersion());
        long autoJob = ItSupport.waitForNewSuccessJob("DOWN 触发自动全量重排（无人调排程接口）",
                baselineJob, Duration.ofSeconds(60));
        expect("自动重排出现在 DOWN 事件之后且 SUCCESS", autoJob > baselineJob, autoJob);
        String afterDown = qScalar("planning_db", """
                SELECT GROUP_CONCAT(DISTINCT COALESCE(machine_id, -1)) FROM task_assignment
                WHERE task_id BETWEEN %d AND %d""".formatted(TASK_LO, TASK_HI));
        expect("重排后 DOWN 资源 720 分配行为 0", "0".equals(qScalar("planning_db", """
                SELECT COUNT(*) FROM task_assignment WHERE task_id BETWEEN %d AND %d AND machine_id=%d
                """.formatted(TASK_LO, TASK_HI, MACHINE_DOWN))), afterDown);
        expect("重排后任务由 721 承接（721 仍在派工）", afterDown != null && afterDown.contains("721"), afterDown);
        expect("planning_db.consumed_event 记录 resource.status.changed（APPLIED 才 ack）",
                consumedResourceEvents(MACHINE_DOWN) == consumedBefore + 1, consumedResourceEvents(MACHINE_DOWN));

        // 恢复回归
        Seed.resetReady(TASK_LO, TASK_HI);
        long consumedBeforeRecover = consumedResourceEvents(MACHINE_DOWN);
        Seed.fireResourceEvent(MACHINE_DOWN, "DOWN", "AVAILABLE", "IT_REPAIR_DONE");
        expect("master_data_db resource 720 恢复 AVAILABLE", pollScalarEquals(
                "master_data_db resource 720 恢复 AVAILABLE", "master_data_db",
                "SELECT status FROM resource WHERE resource_id=" + MACHINE_DOWN, "AVAILABLE", Duration.ofSeconds(20)));
        poll("恢复回写版本再 bump（累计 +2）", Duration.ofSeconds(20), () ->
                masterVersion() == versionBefore + 2 ? null : "version=" + masterVersion());
        ItSupport.waitForNewSuccessJob("AVAILABLE 恢复事件再次自动重排", autoJob, Duration.ofSeconds(60));
        expect("恢复事件 consumed_event 流水 +1",
                consumedResourceEvents(MACHINE_DOWN) == consumedBeforeRecover + 1, consumedResourceEvents(MACHINE_DOWN));

        // 回归实质：恢复后的 720 重新可承接派工（指派约束重新命中 720 → 派工行出现）
        pinMachineRequirement(790_000_011L, MACHINE_DOWN);
        Seed.resetReady(TASK_LO, TASK_HI);
        long regainJob = Seed.scheduleAll(Seed.ts(LocalDate.now().plusDays(2).atTime(8, 0)));
        Seed.awaitJobSuccess("恢复后指派 720 的重排 SUCCESS", regainJob, Duration.ofSeconds(120));
        String regained = qScalar("planning_db", """
                SELECT COUNT(*) FROM task_assignment WHERE task_id=%d AND machine_id=%d"""
                .formatted(790_000_011L, MACHINE_DOWN));
        expect("恢复后 720 重新参与派工（指派 720 的任务成功落派工）", !"0".equals(regained), regained);
    }

    @Test
    @Order(3)
    void maintenanceWritesBackWithoutReschedule() {
        step("== MAINTENANCE 只回写不重排 ==");
        Seed.machine(MACHINE_MAINT, CALENDAR, "IT联动机台723", 180); // 独立运行时自足种子
        long wm = ItSupport.maxJobId();
        long versionBefore = masterVersion();

        Seed.fireResourceEvent(MACHINE_MAINT, "AVAILABLE", "MAINTENANCE", "IT_SCHED_MAINT");
        expect("master_data_db resource 723 回写 MAINTENANCE", pollScalarEquals(
                "master_data_db resource 723 回写 MAINTENANCE", "master_data_db",
                "SELECT status FROM resource WHERE resource_id=" + MACHINE_MAINT, "MAINTENANCE", Duration.ofSeconds(20)));
        poll("MAINTENANCE 回写版本 bump +1", Duration.ofSeconds(20), () ->
                masterVersion() == versionBefore + 1 ? null : "version=" + masterVersion());
        try {
            TimeUnit.SECONDS.sleep(6);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        expect("MAINTENANCE 不触发自动重排（无新 schedule_job）", ItSupport.maxJobId() == wm,
                "maxJobId " + wm + " -> " + ItSupport.maxJobId());
        expect("MAINTENANCE 不置重排 pending 标记", ItSupport.redisGet(CFG.rescheduleMarkerKey) == null,
                ItSupport.redisGet(CFG.rescheduleMarkerKey));

        // 恢复（AVAILABLE 在触发集内 → 会触发一次重排，等待其完成保持栈静默）
        Seed.fireResourceEvent(MACHINE_MAINT, "MAINTENANCE", "AVAILABLE", "IT_MAINT_DONE");
        expect("723 恢复 AVAILABLE", pollScalarEquals(
                "723 恢复 AVAILABLE", "master_data_db",
                "SELECT status FROM resource WHERE resource_id=" + MACHINE_MAINT, "AVAILABLE", Duration.ofSeconds(20)));
        ItSupport.waitForNewSuccessJob("恢复重排 SUCCESS（触发集语义对照）", wm, Duration.ofSeconds(60));
    }

    @Test
    @Order(4)
    void invalidStatusFailsClosedIntoDeadLetter() {
        step("== 非法 toStatus（BROKEN）→ 回写失败不 ack → 重试耗尽 DLX ==");
        Seed.machine(MACHINE_BROKEN, CALENDAR, "IT联动机台722", 150); // 独立运行时自足种子
        long versionBefore = masterVersion();
        long consumedBefore = consumedResourceEvents(MACHINE_BROKEN);
        long deadBefore = deadLetterEvents(MACHINE_BROKEN);
        String statusBefore = qScalar("master_data_db",
                "SELECT status FROM resource WHERE resource_id=" + MACHINE_BROKEN);

        // 登记端点只校验 resourceId 存在性语义：接受登记，合法性由消费侧回写裁决
        Seed.fireResourceEvent(MACHINE_BROKEN, "AVAILABLE", "BROKEN", "IT_DELIBERATE_INVALID");

        poll("planning_db.dead_letter_audit 死信审计入库（重试耗尽进 DLX）", Duration.ofSeconds(30), () ->
                deadLetterEvents(MACHINE_BROKEN) == deadBefore + 1 ? null
                        : "dead_letter=" + deadLetterEvents(MACHINE_BROKEN));
        expect("consumed_event 无新增流水（fail-closed 不 ack）",
                consumedResourceEvents(MACHINE_BROKEN) == consumedBefore, consumedResourceEvents(MACHINE_BROKEN));
        expect("非法回写后 resource 722 状态不变", statusBefore.equals(qScalar("master_data_db",
                "SELECT status FROM resource WHERE resource_id=" + MACHINE_BROKEN)),
                qScalar("master_data_db", "SELECT status FROM resource WHERE resource_id=" + MACHINE_BROKEN));
        expect("非法回写未 bump 版本", masterVersion() == versionBefore, masterVersion());
    }

    // ---------------- 复用的小工具 ----------------

    private static void seedDemandAndTasks(boolean pinBaselineSetups) {
        seedMasterData();
        Seed.capability(PERSON, "SETUP", PRODUCT);
        Seed.capability(PERSON, "INJECT", PRODUCT);
        Seed.capability(PERSON, "POST_QC_PUTAWAY", PRODUCT);
        Seed.capability(WORKSTATION, "POST_QC_PUTAWAY", PRODUCT);
        // SQL 固定段产品（资源链不测产品写入路径；独立运行自足）
        ItSupport.execSql("master_data_db", """
                INSERT INTO product (product_id, product_name, material_code, color_code, create_time)
                VALUES (%d, 'IT联动产品SQL', 'IT-P700S', 'RED', NOW())
                ON DUPLICATE KEY UPDATE material_code=VALUES(material_code);
                INSERT INTO product_mold_param (product_id, mold_id, cycle_time_sec, cavity, yield_rate, utilization)
                VALUES (%d, %d, 30.00, 2, 0.9500, 0.9000)
                ON DUPLICATE KEY UPDATE cycle_time_sec=VALUES(cycle_time_sec);
                INSERT INTO product_route (route_id, product_id, version, is_active, create_time)
                VALUES (%d, %d, 'v1', 1, NOW()) ON DUPLICATE KEY UPDATE is_active=1;
                """.formatted(PRODUCT, PRODUCT, MOLD, ROUTE, PRODUCT));
        ItSupport.registerProduct(PRODUCT);
        Seed.demandSeed(CUSTOMER, "IT联动客户SQL", ORDER, "2026-12-30 00:00:00", 7, LINE, PRODUCT, 60);
        LocalDateTime base = LocalDate.now().plusDays(1).atTime(8, 0);
        // 共享库含其他 AVAILABLE 机台：基线双机台占用用指派约束（requirement.resource_id）确定性达成
        Seed.batch(BATCH_DOWN1, LINE, 30, List.of(
                new Seed.TaskSpec(790_000_011L, "SETUP", 1, 1800, base.minusDays(1), "RULE_SETUP_MACHINE", null,
                        pinBaselineSetups ? MACHINE_DOWN : null),
                new Seed.TaskSpec(790_000_012L, "INJECT", 2, 9, base.plusHours(5), "RULE_INJECT_MACHINE", 790_000_011L),
                new Seed.TaskSpec(790_000_013L, "POST_QC_PUTAWAY", 3, 3600, base.plusHours(5).plusMinutes(9),
                        "RULE_POST_WORKSTATION", 790_000_012L)));
        Seed.batch(BATCH_DOWN2, LINE, 20, List.of(
                new Seed.TaskSpec(790_000_014L, "SETUP", 1, 1200, base.minusDays(1), "RULE_SETUP_MACHINE", null,
                        pinBaselineSetups ? MACHINE_PEER : null),
                new Seed.TaskSpec(790_000_015L, "INJECT", 2, 3, base, "RULE_INJECT_MACHINE", 790_000_014L),
                new Seed.TaskSpec(790_000_016L, "POST_QC_PUTAWAY", 3, 2400, base.plusMinutes(3),
                        "RULE_POST_WORKSTATION", 790_000_015L)));
    }

    /** 解除 IT 段任务的机台指派约束（DOWN 排除场景要求任务可自由重选资源）。 */
    private static void unpinMachineRequirements() {
        ItSupport.execSql("planning_db", """
                UPDATE task_resource_requirement SET resource_id=NULL
                WHERE task_id BETWEEN %d AND %d AND resource_type='MACHINE'
                """.formatted(TASK_LO, TASK_HI));
    }

    private static void pinMachineRequirement(long taskId, long machineId) {
        ItSupport.execSql("planning_db", """
                UPDATE task_resource_requirement SET resource_id=%d
                WHERE task_id=%d AND resource_type='MACHINE'
                """.formatted(machineId, taskId));
    }

    private static long masterVersion() {
        return Long.parseLong(qScalar("master_data_db",
                "SELECT data_version FROM master_data_data_version WHERE scope='MASTER_DATA'"));
    }

    private static long consumedTaskEvents(long taskId) {
        return Long.parseLong(qScalar("planning_db", """
                SELECT COUNT(*) FROM consumed_event WHERE consumer_group='product-planning'
                AND event_type='task.status.changed' AND aggregate_id='%d'""".formatted(taskId)));
    }

    private static long consumedBatchEvents(long batchId) {
        return Long.parseLong(qScalar("demand_db", """
                SELECT COUNT(*) FROM consumed_event WHERE consumer_group='product-demand'
                AND event_type='batch.progress.changed' AND aggregate_id='%d'""".formatted(batchId)));
    }

    private static long consumedResourceEvents(long resourceId) {
        return Long.parseLong(qScalar("planning_db", """
                SELECT COUNT(*) FROM consumed_event WHERE consumer_group='product-planning'
                AND event_type='resource.status.changed' AND aggregate_id='%d'""".formatted(resourceId)));
    }

    private static long deadLetterEvents(long resourceId) {
        return Long.parseLong(qScalar("planning_db", """
                SELECT COUNT(*) FROM dead_letter_audit WHERE consumer_group='product-planning'
                AND event_type='resource.status.changed' AND aggregate_id='%d'""".formatted(resourceId)));
    }

    private static boolean pollScalarEquals(String desc, String db, String sql, String want, Duration timeout) {
        poll(desc, timeout, () -> {
            String v = qScalar(db, sql);
            return want.equals(v) ? null : "当前值=" + v;
        });
        return true;
    }
}
