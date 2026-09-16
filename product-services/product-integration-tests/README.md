# product-integration-tests — 系统/集成级测试宿主

compose + REST 黑盒形态的系统级/集成级测试（PRD：`系统级与集成级测试补全`）。
用例命名 `*IT`，由 maven-failsafe-plugin 执行：

- `mvn test` / `mvn package`：**零影响**（failsafe 绑定 `integration-test`/`verify` 阶段；
  surefire 默认 includes 不含 `*IT`）——270 离线单测基线照旧。
- `mvn verify`（或 `-Dit.test=XxxIT`）：执行集成套件；**IT 栈缺失时显式失败**（前置健康检查），
  禁止静默跳过。

## 前置条件

1. 本机 Docker：`product-mysql` / `product-redis` / `product-nacos` / `product-rabbitmq`
   四件套（`compose.dev.yml`；已健康运行则直接复用，**绝不重启用户常驻容器**）。
2. JDK 17（temurin 17.0.20+，mise 默认 17.0.2 有 cgroup v2 崩溃坑，见 spec
   microservices-platform.md）。
3. 凭据与 `.env.example` 基线一致（mysql root 123456、redis 123456），可用环境变量覆盖。

## 一键脚本

```bash
# 启动：infra 检查（复用健康容器）→ RabbitMQ vhost → schema（缺库才初始化）→
#       6 个服务 fat-jar（独立端口）→ 健康等待；PID 记录到 scratch/integration-tests/pids.txt
product-services/product-integration-tests/scripts/it-up.sh

# 运行（三套件聚合顺序：并发 → 跨服务联动 → 跨班次；可重复连跑）
cd product-services && mvn -B -ntp -pl product-integration-tests verify

# 单类调试
cd product-services && mvn -B -ntp -pl product-integration-tests verify -Dit.test=CrossShiftSchedulingIT

# 停止：只杀自己记录的 PID（infra 容器默认不动；IT_STOP_INFRA=1 才停四件套）
product-services/product-integration-tests/scripts/it-down.sh
```

每次运行前/后套件做**双向清理 + 行数校验**（IT 专属 ID 段全量 + 运行期登记的雪花 ID），
同一栈可连续重跑（AC5 已验证连跑两轮全绿）。

## IT 栈与隔离设计

6 个服务实例使用独立端口：identity 8201 / master-data 8202 / demand 8203 / planning 8204 /
execution 8205 / gateway 8280（用户常驻栈 8101-8105 / 8080 完全不触碰）。
与用户栈共存靠四件套隔离位：

| 面 | IT 栈 | 用户栈 | 机制 |
|---|---|---|---|
| Nacos 注册/发现 | group `PRODUCT_IT_GROUP` | group `PRODUCT_GROUP`（默认） | 双栈互不可见：IT 网关 `lb://` 只解析 IT 实例，用户网关永远解析不到 IT 实例 |
| Redis | database `5` | database `0` | 锁/重排标记/验证码/令牌全隔离（用户 sweeper 看不到 IT 标记，反之亦然） |
| RabbitMQ | vhost `it` | vhost `/` | exchange/queue/DLX 由服务启动时自声明；事件（task/resource/batch）互不投递 |
| MySQL | 共享实例 + 服务库 | 同左 | 黑盒断言需要真实数据面；双向清理 + 行数校验兜底，schedule_job 追加审计不清理 |

> **隔离取舍说明**：曾考虑 `spring.cloud.nacos.discovery.register-enabled=false` +
> 套件全直连端口。但 IT 网关的 `lb://` 路由会经 discovery 解析到**用户栈实例**
> （IT 服务未注册时 Nacos 里只有用户的），服务间 Feign（demand→master-data 等）同理——
> 那会把 IT 流量打进用户栈。故改为**独立 discovery group**（注册开启、组内自闭环）：
> 网关照常黑盒走 `lb://`，跨服务 Feign 也在 IT 栈内闭环，且双向不可见。
> 服务实例与网关同时直接监听独立端口，`/internal/**` 契约端点（网关 404）按既有语义直连。

JWT：identity 私钥留空 → 每次启动生成临时开发密钥（ADR-0003）；全部实例（含网关）
`PRODUCT_SECURITY_JWKS_URI` 指向 IT identity（:8201/jwks），令牌在 IT 栈内自洽。

**快节拍环境变量**（Phase 5/child-2 模式）：planning 实例注入
`PRODUCT_PPS_SCHEDULE_TIMEOUT_SCAN_DELAY_MS=1000`（sweeper 1s 节拍，R4 排空场景依赖）与
`PLANNING_SERVICE_IDENTITY_TOKEN_URL`（指向 IT identity）。

## 套件一览

| 套件 | 场景 | 断言面 |
|---|---|---|
| `ConcurrentSchedulingIT` | 8 并发提交恰好 1 受理 + 7 互斥拒绝；pending 标记运行期保留/完成后 1s 节拍排空；标记 compare-and-delete（陈旧标记覆盖 + 并发双触发收敛） | REST 响应 + planning_db.schedule_job + Redis db5 标记 + master_data_db 版本 |
| `CrossServiceStateLinkageIT` | 异常链（start→三域联动、exception→PAUSED 级联 + reason_code 留痕）；DOWN→回写+自动重排排除→AVAILABLE 回归；MAINTENANCE 只回写不重排；非法 toStatus→不 ack→DLX | REST + 四服务库（planning_db/demand_db/master_data_db/execution_db）JDBC 只读断言 |
| `CrossShiftSchedulingIT` | 班次边界占用（720min 恰满 12h 班次）；溢出跨班次推迟与次日班首承接；SETUP→INJECT→POST 链跨班次（工位分支）；逗号日历 Mon,Wed 排除周二 | REST 排程动作 + planning_db.task_assignment 窗口（DATE_FORMAT 定长比对） |

JDBC 断言通道为**会话级只读**（`SET SESSION TRANSACTION READ ONLY`）；种子/清理走
`docker exec mysql`（与归档 probe 脚本同通道）。

## IT 专属 ID 段（与既有 probe 段互不重叠）

resources 700-799 / calendars 700-749 / products·routes 700-749 / customers 700-749 /
orders·lines 700000-749999 / batches 7300000-7399999 / requirement 76000000-76999999 /
tasks 790000000-799999999（9 位）。API 创建的雪花 ID 运行期登记、随清理删除。
`changeover_rule` rule_id=1 为共享基线种子（probe 同值 upsert），不入清理段。

## 已知边界

- 全量排程/自动重排作用于共享 planning_db 内**全部 READY 任务**：套件按
  「并发 → 跨服务联动 → 跨班次」排序并配任务状态门控，保证窗口数学不受相互干扰；
  用户库遗留 READY 任务可能被顺带重排（与既有 probe 行为一致），断言全部按 IT 段过滤。
- 套件运行要求栈上无其他排程任务长期占用（提交互斥是全局语义）；发现互斥拒绝时
  等待/重跑即可。
- CI：`.github/workflows/integration.yml`（`workflow_dispatch` 手动触发，push 主流程零改动）。
