# 系统级与集成级测试补全 — Design

## 宿主形态（KD1）

- 新 Maven 模块 `product-services/product-integration-tests`：JUnit 5 + failsafe 插件，
  用例命名 `*IT`——`mvn test` 不执行、`mvn verify`（或 `-Dit.test=...`）执行，默认构建零影响。
- 不注册进 CI 的 push 主流程；`.github/workflows/integration.yml` 以 `workflow_dispatch` 触发：
  services 起 mysql/redis/rabbitmq/nacos（对齐 compose 镜像与端口）→ schema init → 起服务 →
  failsafe 跑套件 → 收尾。
- 栈管理脚本（模块内 `scripts/` 或仓库 `scripts/`）：`it-up.sh`（infra 容器 + schema 初始化 +
  六服务 fat-jar 启动，PID 记录）、`it-down.sh`（只杀自己记录的 PID + 可选停容器）——从
  reschedule-trigger/issue-fix 的启动脚本沉淀，环境变量（端口/Redis 密码/JWT）集中一处。
- 服务缺失语义：套件前置检查（health 探测）失败时用例以明确错误失败并输出「先运行 it-up.sh」，
  禁止静默跳过。

## 测试基建（从已验证 probe 模式沉淀为 Java）

- `ItSupport`：验证码登录（GET /captchaImage + Redis 读码，`docker exec` 或直连 redis 的
  可配置二选一——默认 docker exec，与既有脚本一致）、管理员/普通双账号 token 缓存、
  服务身份令牌（走 identity service-token 端点）、REST JSON helper（带 X-Trace-Id 捕获）。
- JDBC 断言助手：直连各服务库（只读 SELECT）做库级状态断言——跨服务联动验收要求库级证据。
- 数据清理：每用例/每套件运行间清理种子外数据（沿用 phase3/6 的双向清理与行数校验做法），
  保证连续重跑（AC5）。

## 场景套件（三流）

1. `CrossServiceStateLinkageIT`（R2）：
   - 异常链：建客户/订单/订单行 → 释放 → generateTask → `POST /execute/event/exception/{id}` →
     断言 REST + planning_db 任务 PAUSED + 批次 IN_PROCESS 级联与 PAUSE 一致 + demand_db
     订单行状态推进 + task_event 行含 reason_code。
   - 资源链：资源 DOWN 事件登记 → master_data_db status=DOWN + 版本 bump → 自动重排新 job
     SUCCESS 且 DOWN 资源分配行为 0 → 恢复事件 → 排回 + 版本再 bump；MAINTENANCE 只回写
     不重排；非法 toStatus → 回写失败不 ack（consumed_event 不增 + DLQ 行存在）。
2. `CrossShiftSchedulingIT`（R3）：写入真实班次日历（跨今日/明日两班次 + break）→ 多任务
   （机台分支 + 工位分支）排程 → 断言跨班次推迟、班次边界窗口、次日班次承接；同时覆盖
   逗号分隔日历模式（规避「Mon-Tue-…」stub quirk 的真实数据形态）。
3. `ConcurrentSchedulingIT`（R4）：真实 Redis + 短节拍环境变量
   （`PLANNING_RECON_INTERVAL_MS`/`product.pps.schedule.timeout-scan-delay-ms` 模式）→
   N 线程并发提交恰好 1 成功 + N-1 互斥拒绝；DOWN 触发标记 + sweeper 排空（缩短节拍实测）；
   标记 compare-and-delete 竞态（新旧标记时序）。

## CI（KD2）

- `.github/workflows/integration.yml`：`workflow_dispatch` only；steps = checkout → temurin
  17 → compose/services 起 infra（或直接 `docker compose up -d nacos rabbitmq mysql redis`）→
  `it-up.sh` → `mvn -pl product-services/product-integration-tests -am verify` → `it-down.sh`
  （always()）。
- push 主流程 `build.yml` 零改动。

## Compatibility / Rollback

- 新模块纯增量：主流程构建/测试/发布零影响；回滚 = 删模块与 workflow 文件。
- failsafe 超时与重试策略保守（单用例超时上限 + 全局 surefire-style 失败即停），避免挂死 CI。
- 测试数据一律走 API/SQL 种子并在收尾清理，不污染用户库（沿用双向清理 + 计数校验）。
