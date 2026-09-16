package com.product.it;

/**
 * IT 栈配置（与 scripts/it-up.sh 启动的隔离栈一一对应）。
 *
 * <p>解析顺序：系统属性（-Dit.*，failsafe 会把命令行 -D 透传到 fork JVM）→ 环境变量
 * （IT_*，CI 用）→ 默认值（= it-up.sh 的固定选择）。默认端口 8280/8201-8205 刻意避开
 * 用户常驻栈的 8080/8101-8105；Redis 库 5、RabbitMQ vhost it、Nacos group PRODUCT_IT_GROUP
 * 为四件套隔离位（见模块 README「隔离设计」）。</p>
 */
public final class ItConfig {

    public final String gatewayBase = cfg("it.gateway", "IT_GATEWAY", "http://127.0.0.1:8280");
    public final String identityBase = cfg("it.identity", "IT_IDENTITY", "http://127.0.0.1:8201");
    public final String masterDataBase = cfg("it.masterdata", "IT_MASTERDATA", "http://127.0.0.1:8202");
    public final String demandBase = cfg("it.demand", "IT_DEMAND", "http://127.0.0.1:8203");
    public final String planningBase = cfg("it.planning", "IT_PLANNING", "http://127.0.0.1:8204");
    public final String executionBase = cfg("it.execution", "IT_EXECUTION", "http://127.0.0.1:8205");

    /** JDBC 只读断言连接（种子/清理不走这条连接，走 docker exec mysql，见 ItSupport）。 */
    public final String mysqlHost = cfg("it.mysql.host", "IT_MYSQL_HOST", "127.0.0.1");
    public final String mysqlPort = cfg("it.mysql.port", "IT_MYSQL_PORT", "33066");
    public final String mysqlUser = cfg("it.mysql.user", "IT_MYSQL_USER", "root");
    public final String mysqlPassword = cfg("it.mysql.password", "IT_MYSQL_PASSWORD", "123456");

    /** 种子/清理 SQL 与 Redis 读写经 docker exec 执行（与归档 probe 脚本同通道）。 */
    public final String mysqlContainer = cfg("it.mysql.container", "IT_MYSQL_CONTAINER", "product-mysql");
    public final String redisContainer = cfg("it.redis.container", "IT_REDIS_CONTAINER", "product-redis");
    public final String redisPassword = cfg("it.redis.password", "IT_REDIS_PASSWORD", "123456");
    /** IT 栈专用 Redis 库（用户栈在 db 0；锁/标记/验证码/令牌随栈隔离）。 */
    public final String redisDb = cfg("it.redis.db", "IT_REDIS_DB", "5");

    public final String adminUser = cfg("it.admin.username", "IT_ADMIN_USERNAME", "admin");
    public final String adminPassword = cfg("it.admin.password", "IT_ADMIN_PASSWORD", "admin123");
    public final String svcUser = cfg("it.svc.username", "IT_SVC_USERNAME", "planning_svc");
    public final String svcPassword = cfg("it.svc.password", "IT_SVC_PASSWORD", "planning-svc-dev-pwd");

    /** 轨迹文件目录（每套件一个文件；验证证据复制到任务 scratch/）。 */
    public final String transcriptDir = cfg("it.transcript.dir", "IT_TRANSCRIPT_DIR", "target/it-transcripts");

    /** planning 重排 pending 标记（RescheduleTriggerService.PENDING_MARKER_KEY）。 */
    public final String rescheduleMarkerKey = "planning:reschedule:pending";

    private static String cfg(String sysProp, String envVar, String def) {
        String v = System.getProperty(sysProp);
        if (v != null && !v.isBlank()) {
            return v.trim();
        }
        v = System.getenv(envVar);
        if (v != null && !v.isBlank()) {
            return v.trim();
        }
        return def;
    }
}
