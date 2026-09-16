package com.product.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 集成测试基建（从 09-14 phase6 e2e_full_chain.py 与 09-16 reschedule e2e_reschedule_probe.py
 * 的已验证 probe 模式沉淀为 Java）：
 *
 * <ul>
 *   <li>验证码登录：GET /captchaImage → docker exec redis 读真实验证码 → POST /login
 *       （管理员账号，token 缓存）；</li>
 *   <li>服务身份令牌：POST /internal/identity/service-token 直连 identity（网关对
 *       /internal/** 显式 404，必须直连端口）；</li>
 *   <li>REST helper：JDK HttpClient + Jackson，捕获 X-Trace-Id（失败信息附最后一次轨迹）；</li>
 *   <li>库级断言：JDBC 只读连接（SET SESSION TRANSACTION READ ONLY）SELECT；
 *       种子/清理 SQL 走 docker exec mysql（与 probe 一致）；</li>
 *   <li>栈前置检查：六个服务健康探测，缺失即显式失败并提示先运行 it-up.sh（禁止静默跳过）；</li>
 *   <li>运行间清理：双向清理（IT 专属固定 ID 段 + 运行期登记的雪花 ID）+ 清理后行数校验，
 *       保证同一栈连续重跑（AC5）；schedule_job 为追加审计表不清理（水位号推进）。</li>
 * </ul>
 */
public final class ItSupport {

    public static final ItConfig CFG = new ItConfig();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** IT 专属 ID 段（与既有 probe 的 790000xx/8100xxx/7000xxx 段互不重叠；任务号 9 位）。 */
    public static final long TASK_LO = 790_000_000L, TASK_HI = 799_999_999L;
    public static final long BATCH_LO = 7_300_000L, BATCH_HI = 7_399_999L;
    public static final long REQ_LO = 76_000_000L, REQ_HI = 76_999_999L;
    public static final long RESOURCE_LO = 700L, RESOURCE_HI = 799L;
    public static final long CALENDAR_LO = 700L, CALENDAR_HI = 749L;
    public static final long PRODUCT_LO = 700L, PRODUCT_HI = 749L;
    public static final long CUSTOMER_LO = 700L, CUSTOMER_HI = 749L;
    public static final long ORDER_LO = 700_000L, ORDER_HI = 749_999L;
    public static final long LINE_LO = 700_000L, LINE_HI = 749_999L;

    // ---------------- 轨迹与断言 ----------------

    private static Transcript transcript;
    private static int passed;
    private static final List<String> FAILURES = new ArrayList<>();
    private static volatile String lastTrace = "(none)";

    /** 单条 REST 响应（JSON 解析失败时 body 为 null，raw 保留原文）。 */
    public record Resp(int status, JsonNode body, String raw, String traceId) {
        public String msg() {
            return body != null && body.hasNonNull("msg") ? body.get("msg").asText() : "";
        }

        public int code() {
            return body != null && body.hasNonNull("code") ? body.get("code").asInt() : -1;
        }

        public String dataAsText() {
            return body != null && body.hasNonNull("data") ? body.get("data").asText() : null;
        }
    }

    private static final class Transcript {
        private final Path file;
        private final StringBuilder buf = new StringBuilder();

        Transcript(Path file) {
            this.file = file;
        }

        synchronized void line(String s) {
            buf.append(s).append('\n');
            System.out.println(s);
        }

        synchronized void flush() {
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, buf.toString(), StandardCharsets.UTF_8);
            } catch (IOException ignored) {
                // 轨迹落盘失败不影响断言本身
            }
        }
    }

    public static void beginSuite(String suiteName) {
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        transcript = new Transcript(Path.of(CFG.transcriptDir, suiteName + "-" + stamp + ".txt"));
        transcript.line("== " + suiteName + " " + LocalDateTime.now() + " ==");
        transcript.line("stack: gw=" + CFG.gatewayBase + " identity=" + CFG.identityBase
                + " masterdata=" + CFG.masterDataBase + " demand=" + CFG.demandBase
                + " planning=" + CFG.planningBase + " execution=" + CFG.executionBase);
    }

    public static void endSuite(String suiteName) {
        if (transcript != null) {
            transcript.line("");
            transcript.line("RESULT: " + suiteName + " pass=" + passed + " fail=" + FAILURES.size()
                    + (FAILURES.isEmpty() ? " ALL GREEN" : " -> " + FAILURES));
            transcript.flush();
        }
    }

    public static void step(String message) {
        if (transcript != null) {
            transcript.line("-- " + message);
        }
    }

    /** 断言：失败抛 AssertionError（携带细节与最后一次 X-Trace-Id），计数进轨迹。 */
    public static void expect(String desc, boolean cond, Object detail) {
        String line = "[" + (cond ? "PASS" : "FAIL") + "] " + desc
                + (cond || detail == null ? "" : "  -> " + detail);
        if (transcript != null) {
            transcript.line(line);
        }
        if (cond) {
            passed++;
            return;
        }
        FAILURES.add(desc);
        throw new AssertionError(desc + " | detail=" + detail + " | lastXTraceId=" + lastTrace);
    }

    public static void expect(String desc, boolean cond) {
        expect(desc, cond, null);
    }

    // ---------------- 栈前置检查（缺失显式失败，禁止静默跳过） ----------------

    /** 六服务健康探测；任一不可达 → 明确失败并指向 it-up.sh。 */
    public static void preflightOrDie() {
        List<String> down = new ArrayList<>();
        for (String base : List.of(CFG.gatewayBase, CFG.identityBase, CFG.masterDataBase,
                CFG.demandBase, CFG.planningBase, CFG.executionBase)) {
            try {
                Resp r = http("GET", base + "/actuator/health", null, null);
                if (r.status() == 200 && r.raw().contains("UP")) {
                    continue;
                }
                down.add(base + " -> HTTP " + r.status() + " " + r.raw());
            } catch (Exception e) {
                down.add(base + " -> " + e.getMessage());
            }
        }
        expect("IT 栈健康检查（6/6 服务 UP）", down.isEmpty(),
                "以下服务不可用，请先运行 product-services/product-integration-tests/scripts/it-up.sh："
                        + down + "（前置缺失必须显式失败，本套件不允许静默跳过）");
    }

    // ---------------- 登录与令牌 ----------------

    private static volatile String adminToken;
    private static volatile String svcToken;

    /** 验证码登录（管理员）：真实验证码经 Redis 服务端值校验，与 probe 模式一致。 */
    public static String adminToken() {
        if (adminToken == null) {
            adminToken = captchaLogin(CFG.adminUser, CFG.adminPassword);
        }
        return adminToken;
    }

    /** 服务身份令牌（planning_svc，直连 identity 内部端点；网关侧 /internal/** 为 404）。 */
    public static String svcToken() {
        if (svcToken == null) {
            Resp r = http("POST", CFG.identityBase + "/internal/identity/service-token",
                    Map.of("username", CFG.svcUser, "password", CFG.svcPassword), null);
            expect("服务身份令牌签发（/internal/identity/service-token）",
                    r.status() == 200 && r.code() == 200 && r.dataAsText() != null, r.raw());
            svcToken = r.dataAsText();
        }
        return svcToken;
    }

    public static String captchaLogin(String username, String password) {
        Resp cap = http("GET", CFG.gatewayBase + "/captchaImage", null, null);
        expect("GET /captchaImage 匿名可访问并返回 uuid", cap.status() == 200
                && cap.body() != null && cap.body().hasNonNull("uuid"), cap.raw());
        String uuid = cap.body().get("uuid").asText();
        String code = redisGet("identity:captcha_codes:" + uuid);
        expect("Redis 可读到服务端验证码（identity:captcha_codes）", code != null && !code.isBlank(), String.valueOf(code));
        Resp login = http("POST", CFG.gatewayBase + "/login",
                Map.of("username", username, "password", password, "code", code, "uuid", uuid), null);
        expect("POST /login（验证码校验）返回 token", login.status() == 200
                && login.body() != null && login.body().hasNonNull("token"), login.raw());
        return login.body().get("token").asText();
    }

    // ---------------- REST helper ----------------

    public static Resp http(String method, String url, Object body, String token) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json");
            if (token != null) {
                b.header("Authorization", "Bearer " + token);
            }
            if (body != null) {
                b.header("Accept", "application/json");
                b.method(method, HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
            } else {
                b.method(method, HttpRequest.BodyPublishers.noBody());
            }
            HttpResponse<byte[]> resp = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
            String raw = new String(resp.body(), StandardCharsets.UTF_8);
            String trace = resp.headers().firstValue("X-Trace-Id").orElse(null);
            if (trace != null && !trace.isBlank()) {
                lastTrace = trace;
            }
            JsonNode json;
            try {
                json = MAPPER.readTree(raw);
            } catch (Exception parseFailure) {
                json = null;
            }
            return new Resp(resp.statusCode(), json, raw, trace);
        } catch (IOException e) {
            throw new RuntimeException("REST 调用失败: " + method + " " + url + " -> " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("REST 调用被中断: " + url, e);
        }
    }

    /** 携管理员 token 的业务调用（经 IT 网关）。 */
    public static Resp api(String method, String path, Object body) {
        return http(method, CFG.gatewayBase + path, body, adminToken());
    }

    /** 无请求体的 GET 便捷重载。 */
    public static Resp api(String method, String path) {
        return api(method, path, null);
    }

    /** 直连某服务的业务/内部调用（execution /internal 等，网关不可达路径）。 */
    public static Resp direct(String base, String method, String path, Object body, String token) {
        return http(method, base + path, body, token);
    }

    // ---------------- 轮询等待 ----------------

    /** 轮询直到 check 返回 null（成功）或超时（以最后一次 detail 失败）。 */
    public static void poll(String desc, Duration timeout, Supplier<String> check) {
        long deadline = System.nanoTime() + timeout.toNanos();
        String detail = "(no probe)";
        while (System.nanoTime() < deadline) {
            detail = check.get();
            if (detail == null) {
                expect(desc, true);
                return;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        expect(desc, false, detail + " (等待 " + timeout.getSeconds() + "s 超时)");
    }

    // ---------------- docker exec：SQL 种子/清理 与 Redis ----------------

    public static String sh(String... args) {
        try {
            Process p = new ProcessBuilder(args).redirectErrorStream(false).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean done = p.waitFor(60, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                throw new RuntimeException("命令超时: " + String.join(" ", args));
            }
            if (p.exitValue() != 0) {
                throw new RuntimeException("命令失败(" + p.exitValue() + "): " + String.join(" ", args)
                        + " | stderr=" + err.strip());
            }
            return out.strip();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException("命令执行失败: " + String.join(" ", args) + " -> " + e.getMessage(), e);
        }
    }

    /** 多语句种子/清理 SQL（docker exec mysql，与 probe 脚本同通道；仅允许作用于 IT 专属段）。 */
    public static void execSql(String db, String script) {
        List<String> args = new ArrayList<>(List.of("docker", "exec", CFG.mysqlContainer, "mysql",
                "-uroot", "-p" + CFG.mysqlPassword, "--default-character-set=utf8mb4", "-N", "-B", "-e", script));
        if (db != null && !db.isBlank()) {
            args.add(db);
        }
        sh(args.toArray(new String[0]));
    }

    public static String redis(String... redisArgs) {
        List<String> args = new ArrayList<>(List.of("docker", "exec", CFG.redisContainer, "redis-cli",
                "-a", CFG.redisPassword, "--no-auth-warning", "-n", CFG.redisDb));
        args.addAll(List.of(redisArgs));
        return sh(args.toArray(new String[0]));
    }

    public static String redisGet(String key) {
        String out = redis("GET", key);
        if ("(nil)".equals(out) || out.isBlank()) {
            return null;
        }
        // RedisCache 以 JSON 序列化存储（字符串值带引号），与 probe 的 strip('"') 同语义
        if (out.length() >= 2 && out.charAt(0) == '"' && out.charAt(out.length() - 1) == '"') {
            return out.substring(1, out.length() - 1);
        }
        return out;
    }

    public static void redisSetTtl(String key, String value, int ttlSeconds) {
        redis("SET", key, value, "EX", String.valueOf(ttlSeconds));
    }

    public static void redisDel(String key) {
        redis("DEL", key);
    }

    // ---------------- JDBC 只读断言 ----------------

    private static final java.util.concurrent.ConcurrentHashMap<String, Connection> RO_CONNECTIONS =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static Connection ro(String db) {
        return RO_CONNECTIONS.computeIfAbsent(db, d -> {
            try {
                String url = "jdbc:mysql://" + CFG.mysqlHost + ":" + CFG.mysqlPort + "/" + d
                        + "?useUnicode=true&characterEncoding=UTF-8&useSSL=false&allowPublicKeyRetrieval=true"
                        + "&serverTimezone=Asia/Taipei&connectTimeout=10000&socketTimeout=30000";
                Connection c = DriverManager.getConnection(url, CFG.mysqlUser, CFG.mysqlPassword);
                c.setReadOnly(true);
                try (Statement st = c.createStatement()) {
                    // 会话级只读：断言通道对库零写入（写通道只有 docker exec 种子/清理）
                    st.execute("SET SESSION TRANSACTION READ ONLY");
                }
                return c;
            } catch (Exception e) {
                throw new RuntimeException("JDBC 只读连接创建失败（mysql " + CFG.mysqlHost + ":"
                        + CFG.mysqlPort + "/" + d + "）: " + e.getMessage(), e);
            }
        });
    }

    /** 单值查询（字符串化；空集返回 null）。 */
    public static String qScalar(String db, String sql) {
        try (Statement st = ro(db).createStatement(); ResultSet rs = st.executeQuery(sql)) {
            if (!rs.next()) {
                return null;
            }
            Object v = rs.getObject(1);
            return v == null ? null : String.valueOf(v);
        } catch (Exception e) {
            throw new RuntimeException("只读查询失败 [" + db + "] " + sql + " -> " + e.getMessage(), e);
        }
    }

    // ---------------- 排程任务水位与自动重排等待 ----------------

    public static long maxJobId() {
        return Long.parseLong(qScalar("planning_db", "SELECT COALESCE(MAX(job_id),0) FROM schedule_job"));
    }

    /** 等待出现 job_id &gt; watermark 且 SUCCESS 的排程任务（自动重排触发的核心证据）。 */
    public static long waitForNewSuccessJob(String desc, long watermark, Duration timeout) {
        final long[] found = {-1};
        poll(desc, timeout, () -> {
            String v = qScalar("planning_db", "SELECT job_id FROM schedule_job WHERE job_id > " + watermark
                    + " AND status = 'SUCCESS' ORDER BY job_id ASC LIMIT 1");
            if (v != null) {
                found[0] = Long.parseLong(v);
                return null;
            }
            String latest = qScalar("planning_db", "SELECT CONCAT(job_id,':',status,':',IFNULL(error_message,''))"
                    + " FROM schedule_job WHERE job_id > " + watermark + " ORDER BY job_id DESC LIMIT 1");
            return "尚无 SUCCESS 新任务（最新: " + latest + "）";
        });
        return found[0];
    }

    // ---------------- 运行间清理（双向清理 + 行数校验） ----------------

    private static final TreeSet<Long> TASKS = new TreeSet<>();
    private static final TreeSet<Long> BATCHES = new TreeSet<>();
    private static final TreeSet<Long> LINES = new TreeSet<>();
    private static final TreeSet<Long> ORDERS = new TreeSet<>();
    private static final TreeSet<Long> CUSTOMERS = new TreeSet<>();
    private static final TreeSet<Long> PRODUCTS = new TreeSet<>();

    public static synchronized void registerTask(long id) {
        TASKS.add(id);
    }

    public static synchronized void registerTasks(Iterable<Long> ids) {
        for (long id : ids) {
            TASKS.add(id);
        }
    }

    public static synchronized void registerBatch(long id) {
        BATCHES.add(id);
    }

    public static synchronized void registerLine(long id) {
        LINES.add(id);
    }

    public static synchronized void registerOrder(long id) {
        ORDERS.add(id);
    }

    public static synchronized void registerCustomer(long id) {
        CUSTOMERS.add(id);
    }

    public static synchronized void registerProduct(long id) {
        PRODUCTS.add(id);
    }

    public static synchronized void registerCalendar(long id) {
        // 日历清理按 700-749 固定段全量删除；登记仅用于校验面一致性
        CALENDARS.add(id);
    }

    private static final TreeSet<Long> CALENDARS = new TreeSet<>();

    /** 运行间/运行前清理：IT 专属固定段全量 + 登记雪花 ID + 重排标记，随后行数校验必须归零。 */
    public static synchronized void cleanupAll() {
        // 重排标记（fixture 或遗留触发），TTL 兜底之外主动清除，保证套件静默期无补跑
        redisDel(CFG.rescheduleMarkerKey);

        String taskIn = inList(TASKS);
        String taskAggIn = inStrList(TASKS);
        String batchAggIn = inStrList(BATCHES);
        String lineAggIn = inStrList(LINES);
        String orderAggIn = inStrList(ORDERS);

        String taskRange = "task_id BETWEEN " + TASK_LO + " AND " + TASK_HI;
        String batchRange = "batch_id BETWEEN " + BATCH_LO + " AND " + BATCH_HI;
        String resourceRange = "resource_id BETWEEN " + RESOURCE_LO + " AND " + RESOURCE_HI;
        String taskAggRange = "aggregate_id BETWEEN '" + TASK_LO + "' AND '" + TASK_HI + "'";
        String resAggRange = "aggregate_id BETWEEN '" + RESOURCE_LO + "' AND '" + RESOURCE_HI + "'";
        String batchAggRange = "aggregate_id BETWEEN '" + BATCH_LO + "' AND '" + BATCH_HI + "'";

        // ---- planning_db（子表 → 主表；固定段优先，登记雪花 ID 补充） ----
        List<String> planning = new ArrayList<>(List.of(
                "DELETE FROM task_assignment_resource WHERE " + taskRange + orIn("task_id", taskIn),
                "DELETE FROM task_assignment WHERE " + taskRange + orIn("task_id", taskIn),
                "DELETE FROM task_resource_requirement WHERE requirement_id BETWEEN " + REQ_LO + " AND " + REQ_HI
                        + " OR " + taskRange + orIn("task_id", taskIn),
                "DELETE FROM task_dependency WHERE pre_task_id BETWEEN " + TASK_LO + " AND " + TASK_HI
                        + " OR post_task_id BETWEEN " + TASK_LO + " AND " + TASK_HI
                        + orIn2("pre_task_id", "post_task_id", taskIn),
                "DELETE FROM operation_task WHERE " + taskRange + " OR " + batchRange + orIn("task_id", taskIn),
                "DELETE FROM production_batch WHERE " + batchRange + orIn("batch_id", inList(BATCHES)),                "DELETE FROM consumed_event WHERE event_type IN "
                        + "('task.status.changed','resource.status.changed') AND ("
                        + taskAggRange + " OR " + resAggRange
                        + (taskAggIn.isEmpty() ? "" : " OR aggregate_id IN (" + taskAggIn + ")") + ")",
                "DELETE FROM dead_letter_audit WHERE event_type = 'resource.status.changed' AND " + resAggRange,
                "DELETE FROM event_outbox WHERE event_type = 'batch.progress.changed' AND ("
                        + batchAggRange + (batchAggIn.isEmpty() ? "" : " OR aggregate_id IN (" + batchAggIn + ")") + ")"
        ));
        execSql("planning_db", String.join(";\n", planning));

        // ---- demand_db ----
        execSql("demand_db", String.join(";\n", List.of(
                "DELETE FROM planning_batch_state WHERE " + batchRange + orIn("batch_id", inList(BATCHES)),
                "DELETE FROM order_line WHERE order_line_id BETWEEN " + LINE_LO + " AND " + LINE_HI
                        + orIn("order_line_id", inList(LINES)),
                "DELETE FROM customer_order WHERE order_id BETWEEN " + ORDER_LO + " AND " + ORDER_HI
                        + orIn("order_id", inList(ORDERS)),
                "DELETE FROM customer WHERE customer_id BETWEEN " + CUSTOMER_LO + " AND " + CUSTOMER_HI
                        + orIn("customer_id", inList(CUSTOMERS)),
                "DELETE FROM consumed_event WHERE event_type = 'batch.progress.changed' AND ("
                        + batchAggRange + (batchAggIn.isEmpty() ? "" : " OR aggregate_id IN (" + batchAggIn + ")") + ")",
                "DELETE FROM event_outbox WHERE event_type IN "
                        + "('order.progress.changed','order_line.progress.changed') AND ("
                        + "aggregate_id BETWEEN '" + ORDER_LO + "' AND '" + ORDER_HI + "'"
                        + " OR aggregate_id BETWEEN '" + LINE_LO + "' AND '" + LINE_HI + "'"
                        + (orderAggIn.isEmpty() && lineAggIn.isEmpty() ? ""
                        : (orderAggIn.isEmpty() ? "" : " OR aggregate_id IN (" + orderAggIn + ")")
                        + (lineAggIn.isEmpty() ? "" : " OR aggregate_id IN (" + lineAggIn + ")")) + ")"
        )));

        // ---- master_data_db ----
        String productIn = inList(PRODUCTS);
        execSql("master_data_db", String.join(";\n", List.of(
                "DELETE FROM resource_capability WHERE (resource_id BETWEEN " + RESOURCE_LO + " AND " + RESOURCE_HI
                        + ") OR (product_id BETWEEN " + PRODUCT_LO + " AND " + PRODUCT_HI + ")"
                        + orIn("product_id", productIn),
                "DELETE FROM machine_mold_compatibility WHERE machine_id BETWEEN " + RESOURCE_LO
                        + " AND " + RESOURCE_HI,
                "DELETE FROM machine WHERE machine_id BETWEEN " + RESOURCE_LO + " AND " + RESOURCE_HI,
                "DELETE FROM mold WHERE mold_id BETWEEN " + RESOURCE_LO + " AND " + RESOURCE_HI,
                "DELETE FROM product_mold_param WHERE product_id BETWEEN " + PRODUCT_LO + " AND " + PRODUCT_HI
                        + orIn("product_id", productIn),
                "DELETE FROM route_operation WHERE route_id BETWEEN " + PRODUCT_LO + " AND " + PRODUCT_HI
                        + orIn("route_id", productIn),
                "DELETE FROM product_route WHERE product_id BETWEEN " + PRODUCT_LO + " AND " + PRODUCT_HI
                        + orIn("product_id", productIn),
                "DELETE FROM product WHERE product_id BETWEEN " + PRODUCT_LO + " AND " + PRODUCT_HI
                        + orIn("product_id", productIn),
                "DELETE FROM calendar WHERE calendar_id BETWEEN " + CALENDAR_LO + " AND " + CALENDAR_HI,
                "DELETE FROM resource WHERE " + resourceRange
        )));

        // ---- execution_db ----
        execSql("execution_db", String.join(";\n", List.of(
                "DELETE FROM task_event WHERE " + taskRange + orIn("task_id", taskIn),
                "DELETE FROM resource_status_event WHERE " + resourceRange,
                "DELETE FROM event_outbox WHERE event_type IN "
                        + "('task.status.changed','resource.status.changed') AND ("
                        + taskAggRange + " OR " + resAggRange
                        + (taskAggIn.isEmpty() ? "" : " OR aggregate_id IN (" + taskAggIn + ")") + ")"
        )));

        verifyCleanup();
    }

    /** 清理后行数校验（计数必须归零；schedule_job 追加审计不清理、不在校验范围）。 */
    private static void verifyCleanup() {
        List<String> checks = List.of(
                "planning_db|operation_task|task_id BETWEEN " + TASK_LO + " AND " + TASK_HI,
                "planning_db|production_batch|batch_id BETWEEN " + BATCH_LO + " AND " + BATCH_HI,
                "planning_db|task_resource_requirement|requirement_id BETWEEN " + REQ_LO + " AND " + REQ_HI,
                "demand_db|order_line|order_line_id BETWEEN " + LINE_LO + " AND " + LINE_HI,
                "demand_db|customer_order|order_id BETWEEN " + ORDER_LO + " AND " + ORDER_HI,
                "demand_db|customer|customer_id BETWEEN " + CUSTOMER_LO + " AND " + CUSTOMER_HI,
                "master_data_db|resource|resource_id BETWEEN " + RESOURCE_LO + " AND " + RESOURCE_HI,
                "master_data_db|product|product_id BETWEEN " + PRODUCT_LO + " AND " + PRODUCT_HI,
                "master_data_db|calendar|calendar_id BETWEEN " + CALENDAR_LO + " AND " + CALENDAR_HI,
                "execution_db|task_event|task_id BETWEEN " + TASK_LO + " AND " + TASK_HI,
                "execution_db|resource_status_event|resource_id BETWEEN " + RESOURCE_LO + " AND " + RESOURCE_HI
        );
        List<String> residual = new ArrayList<>();
        for (String c : checks) {
            String[] parts = c.split("\\|", 3);
            String n = qScalar(parts[0], "SELECT COUNT(*) FROM " + parts[1] + " WHERE " + parts[2]);
            if (!"0".equals(n)) {
                residual.add(parts[0] + "." + parts[1] + " = " + n);
            }
        }
        expect("运行间清理行数校验（IT 专属段全部归零）", residual.isEmpty(), "残留: " + residual);
        TASKS.clear();
        BATCHES.clear();
        LINES.clear();
        ORDERS.clear();
        CUSTOMERS.clear();
        PRODUCTS.clear();
        CALENDARS.clear();
    }

    private static String orIn(String column, String in) {
        return in.isEmpty() ? "" : " OR " + column + " IN (" + in + ")";
    }

    private static String orIn2(String col1, String col2, String in) {
        return in.isEmpty() ? "" : " OR " + col1 + " IN (" + in + ") OR " + col2 + " IN (" + in + ")";
    }

    private static String inList(Iterable<Long> ids) {
        StringBuilder sb = new StringBuilder();
        for (long id : ids) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(id);
        }
        return sb.toString();
    }

    private static String inStrList(Iterable<Long> ids) {
        StringBuilder sb = new StringBuilder();
        for (long id : ids) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append('\'').append(id).append('\'');
        }
        return sb.toString();
    }

    private ItSupport() {
    }
}
