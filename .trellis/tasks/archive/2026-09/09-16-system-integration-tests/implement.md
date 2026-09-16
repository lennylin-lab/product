# 系统级与集成级测试补全 — Implementation Plan

> 阅读顺序：implement.jsonl → 本任务 design.md → prd.md → 三份归档 probe 脚本
> （archive/2026-09/…/scratch/phase6/scripts、09-16-reschedule-trigger/scratch）作为模式来源 →
> microservices-platform.md（记录纪律）/ quality-guidelines.md。

## Checklist（按序执行）

- [x] 1. 宿主模块：`product-integration-tests`（failsafe `*IT`、默认构建零影响、依赖收敛：
  spring-test REST client / JDBC mysql driver / jackson；断言 JUnit 5）。
- [x] 2. 测试基建：`ItSupport`（双账号登录、服务身份令牌、REST helper、X-Trace-Id 捕获）、
  JDBC 只读断言助手、栈前置检查（缺失即明确失败）、运行间清理。
- [x] 3. 栈脚本：`it-up.sh` / `it-down.sh`（PID 记录、schema init、环境变量集中；进程安全约束
  同既有约定）。
- [x] 4. `CrossServiceStateLinkageIT`（R2 场景全集，REST + 库级断言）。
- [x] 5. `CrossShiftSchedulingIT`（真实日历跨班次场景）。
- [x] 6. `ConcurrentSchedulingIT`（真实 Redis 并发 + sweeper 排空 + 标记竞态）。
- [x] 7. 本地实测：起栈连跑两轮全绿（AC5）；transcript 存任务 scratch/。
- [x] 8. CI：`.github/workflows/integration.yml`（workflow_dispatch）；本地无法触发验证，
  推送后由用户/主会话手动 dispatch 确认首跑。
- [x] 9. 文档：README 或模块内 README 的前置条件与运行说明；`docs/TODO.md` P2 行收尾。

## Validation Commands

- `cd product-services && mvn -B -ntp clean test`（270 离线基线零回归；failsafe 不参与）
- `mvn -B -ntp -pl product-services/product-integration-tests -am verify`（起栈后，IT 全绿；
  连跑两轮）
- 根仓库 `mvn -B -ntp -DskipTests compile`（单体回滚点 28/28，新模块纳入后 29/29——以实际为准记录）

## Review Gates

- 默认构建/离线测试零影响（`mvn test` 不触碰 `*IT`）——硬门禁。
- 进程安全：只动自己记录的 PID/容器；用户运行中的栈（8080/8101-8105，pids
  scratch/reschedule-trigger/pids.txt）如需复用先确认 jar 版本，否则用自己的端口起第二套。
- 断言不得因环境抖动静默通过（缺失前置必须显式失败）。
- 勾选后交 trellis-check 校验（含连跑两轮证据与 transcript 抽查）。

## Rollback Points

- 新模块与 workflow 文件纯增量，回滚 = 删除；运行库数据经清理脚本与计数校验保证无残留。

---

## 执行记录（2026-09-16，trellis-implement）

### 文件清单（全部纯增量，除两处注明）

- 新模块 `product-services/product-integration-tests/`：
  - `pom.xml`（failsafe 3.2.5，includes 仅 `IntegrationSuiteIT.java`，forkedProcessTimeoutInSeconds=3600；
    依赖 junit-jupiter + junit-platform-suite + jackson-databind + mysql-connector-j，全部 test scope）
  - `src/test/java/com/product/it/`：`ItConfig`、`ItSupport`、`Seed`、`IntegrationSuiteIT`（@Suite 聚合，
    @TestClassOrder 显式类序）、`ConcurrentSchedulingIT`、`CrossServiceStateLinkageIT`、`CrossShiftSchedulingIT`
  - `scripts/it-up.sh`、`scripts/it-down.sh`
  - `README.md`（前置条件/运行说明/隔离设计/ID 段/已知边界）
- `product-services/pom.xml`：modules 追加 `product-integration-tests`（唯一对既有文件的改动之一）
- `.github/workflows/integration.yml`（workflow_dispatch only；`build.yml` 零改动）
- `docs/TODO.md`：P2「补系统级与集成级测试」行收尾（唯一另一处改动）
- 生产代码（main 源集、schema.sql、错误契约）：**零改动**（git status 仅上述两项 M + 新增）

### 关键设计事实

- **隔离**（README「隔离设计」表）：独立端口 8201-8205/8280；Nacos discovery group `PRODUCT_IT_GROUP`；
  Redis database 5；RabbitMQ vhost `it`；MySQL 共享（双向清理兜底）。
  与派发说明的偏差：**未采用 `register-enabled=false` + 全直连**——若 IT 服务不注册，IT 网关与
  服务间 Feign 的 `lb://` 会经 discovery 解析到用户栈实例（发现组只有用户注册时必然如此），
  会把 IT 流量打进用户运行中的服务。改为独立 discovery group 双向不可见（README 有完整论证）。
- `ItSupport`：captcha 登录（docker exec redis 读真实验证码，JSON 引号剥离）、planning_svc
  服务身份令牌（直连 /internal/identity/service-token）、REST helper（X-Trace-Id 捕获，失败附最后轨迹）、
  JDBC **会话级只读**断言连接（`SET SESSION TRANSACTION READ ONLY`，按库缓存）、preflight 六服务
  健康检查（缺失即 AssertionError + 「先运行 it-up.sh」提示）、双向清理 + 行数校验（IT 段
  resources 700-799 / batches 7300000+ / tasks 790000000+（9 位）等，与既有 probe 段不重叠；
  API 雪花 ID 运行期登记）。
- 排序门控：@Suite 类序 = 并发 → 跨服务联动 → 跨班次（前两者会触发自动重排，跨班次窗口数学
  必须最后独占运行）。依赖约束语义：绑定库内既有前序 planned_end，同轮内按 earliest_start 定序
  （probe 同形，实测验证）。
- sweeper 快节拍：planning 实例 `PRODUCT_PPS_SCHEDULE_TIMEOUT_SCAN_DELAY_MS=1000`（1s），
  R4 排空断言依赖（Phase 5/child-2 环境变量模式）。

### 验证证据（scratch/ 均为本任务 `scratch/`）

- **AC1 默认构建零影响**：`cd product-services && mvn -B -ntp clean test` → 270 离线用例
  （9 个模块汇总行合计 270，全绿），`product-integration-tests` 以 [14/14] 构建成功、
  surefire 对 `*IT` 零发现（`*IT` 不匹配 surefire 默认 includes，failsafe 仅绑定 verify 阶段）、
  failsafe 不出现于 test 阶段（scratch/product-services-clean-test.log）。
  仓库根 `mvn -B -ntp clean test` → 325 离线用例全绿（270 + 单体 55），com.product.it 零执行
  （scratch/root-clean-test.log）。
- **AC7/compile**：根 `mvn -B -ntp -DskipTests compile` → **29/29** 模块 SUCCESS（28 基线 + 新模块，
  以实际为准；scratch/root-compile.log）。
- **AC5 可重复性**：同一 IT 栈（it-up.sh 一次拉起，未重建）连续 **3 轮** `mvn -pl
  product-integration-tests verify` 全绿：`Tests run: 10, Failures: 0, Errors: 0, Skipped: 0` × 3
  （后两轮 console 存 scratch/it-run2-console.log、it-run3-console.log；每轮清理行数校验断言
  「IT 专属段全部归零」通过）。
- **AC2/AC3/AC4**：10 用例全绿。套件级 transcript（每轮三套件各一份，含逐条 [PASS]）：
  scratch/transcripts/{ConcurrentSchedulingIT-20260916-082334, CrossServiceStateLinkageIT-20260916-082353,
  CrossShiftSchedulingIT-20260916-082411}.txt 等（run2/run3 两套全绿组）；断言计数
  Concurrent 38 / CrossService 68 / CrossShift 29（合计 135；注意 ItSupport 的 RESULT 计数器
  为跨套件累计值：38 → 106 → 135）；failsafe 原始报告 scratch/failsafe-reports-run3/。
- **进程安全**：用户栈 pids 2040244-2040249 全程存活（验证时 uptime 02:37:34 未重启），
  8080/8101-8105 仍属用户进程；IT 栈 8201-8205/8280 独立端口，pids 记录
  `scratch/integration-tests/pids.txt`（it-down.sh 只杀该文件 PID 且校验 cmdline）。
- **AC6 CI**：`.github/workflows/integration.yml` 就绪（workflow_dispatch；steps = checkout →
  temurin 17 → `-DskipTests package` → it-up.sh → failsafe verify → it-down.sh(always) →
  归档 transcript/failsafe 报告(always)）。本地无法触发，**推送后需用户/主会话手动 dispatch 首跑确认**。

### 已知边界与说明

- 全量排程/自动重排作用于共享 planning_db 内全部 READY 任务（全局语义）：用户库遗留 READY 任务
  可能被顺带重排（与既有 probe 行为一致）；全部断言按 IT 段过滤，不读写用户行。
- 互斥拒绝→标记保留的「真实事件竞态」分支未做端到端（时序脆弱）；以确定性夹具等价覆盖：
  任务运行中置标记 + 三次观测「标记保留且无补跑」+ 完成后 1s 节拍排空（ConcurrentSchedulingIT
  sweeperDrainsPendingMarkerWithShortenedCadence），并留 compare-and-delete 收敛场景。
- 单体回滚点 28/28 已成为历史值；新模块入库后为 29/29（实际记录见上）。
