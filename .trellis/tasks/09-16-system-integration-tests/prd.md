# 系统级与集成级测试补全

## Goal

把散落在各阶段归档 scratch 中的一次性验证脚本，沉淀为可重复执行的系统级/集成级测试套件（compose+REST 黑盒形态），覆盖三类缺口：跨服务数据库状态联动、跨班次排程场景、并发排程场景。

## Confirmed Facts（仓库证据，2026-09-16 调查）

- 现有测试全部为离线 Mockito 单测（spring-boot-starter-test，无 Testcontainers）；`mvn test` 270 用例不依赖基础设施。
- 实时端到端验证已被反复证明但不可重复维护：phase5 事件链（10 步对拍）、phase6 全链 E2E（42 断言）、reschedule-trigger 探测（21+6 项）——均为一次性 python/curl 脚本，散在各归档任务 scratch/。
- 可复用资产：compose 全套基础设施、服务身份令牌签发、验证码登录辅助、契约对拍/事件链 probe 模式。
- 并发排程只有 mock 级测试（Redis 锁/互斥/标记排空均为 stub）；真实 Redis 并发未测。
- 跨班次：计算器单测用日历 stub（存在「Mon-Tue-…」解析 quirk，夹具 child-3 记录）；真实日历系统级场景未测。
- 跨服务状态联动（EXCEPTION→PAUSED→级联、DOWN→回写→重排排除→恢复）已实现并有 live 探测，同为一次性脚本。
- CI 为 GitHub Actions 单 job（temurin 17 + `mvn package`），无 services 容器。

## Key Decisions（用户已决，2026-09-16）

- **KD1**：套件形态 = **compose 栈上的 REST 黑盒集成测试**——新建集成测试模块（JUnit，failsafe `*IT` 命名），动作走 REST、状态断言可下探 JDBC（跨服务 DB 联动验证需要库级断言）；复用已验证的 probe 模式；默认 `mvn test/package` 不受影响。否决 Testcontainers 全自动（Nacos v3 容器工程量大）。
- **KD2**：CI 策略 = **GitHub Actions `workflow_dispatch` 手动触发**的集成 job（services 起 infra + 跑套件），push 主流程保持现状；本地提供一键脚本。

## Requirements

- R1 — 新建集成测试宿主模块：failsafe `*IT` 命名、默认构建零影响、测试基建（登录/服务身份/REST/JDBC 断言助手）从已验证 probe 模式沉淀。
- R2 — 跨服务状态联动套件：EXCEPTION→任务 PAUSED→批次/订单行级联；资源 DOWN→回写→自动重排→DOWN 资源排除→恢复回归；断言下探各服务库（planning/execution/master_data/demand_db）。
- R3 — 跨班次排程套件：真实日历/班次数据、多任务跨班次推迟、班次边界占用窗口。
- R4 — 并发排程套件：真实 Redis 锁下 N 并发提交恰好一成功余互斥拒绝；sweeper 排空（环境变量缩短节拍，沿用 Phase 5 做法）；标记竞态。
- R5 — 环境与可重复性：一键启动/清理脚本（栈 + schema + 服务 + 种子）、套件可连续重跑（运行间清理）、前置条件文档化；栈缺失时用例明确报错而非静默跳过。
- R6 — CI：`workflow_dispatch` 集成 job；push 主流程与 270 离线单测零回归。

## Acceptance Criteria

- [ ] AC1 — 集成模块入库；默认 `mvn test`/`package` 行为不变（270 离线用例照常），`mvn verify`/failsafe 才执行 `*IT`。
- [ ] AC2 — 跨服务联动 `*IT`：EXCEPTION 级联与 DOWN/恢复重排链全过，断言含 REST 响应与跨服务库级状态（每服务库至少一次库级断言）。
- [ ] AC3 — 跨班次 `*IT`：真实日历下多任务跨班次推迟与班次边界窗口断言全过。
- [ ] AC4 — 并发 `*IT`：真实 Redis 下并发提交「恰好一成功、其余互斥拒绝」、sweeper 排空与标记竞态断言全过。
- [ ] AC5 — 可重复性：同一栈上连续重跑两轮全绿（第二轮前无需重建栈，仅运行间清理）；前置条件与一键脚本入库。
- [ ] AC6 — CI：`workflow_dispatch` 集成 job 就绪；推送后远端手动触发一次跑绿（本地无法触发，推送后确认）。
- [ ] AC7 — 回归：270 离线单测与单体 28/28 编译零变化。

## Out of Scope

- 性能/压测；ELK/Jaeger 断言自动化；前端 E2E；Testcontainers。

## Task Notes

- 单任务（三条场景流共享同一套件基建，拆子任务会使基建重复）。
- 实测需要起栈：沿用本会话既定的进程安全约束（只动自己记录的 PID/容器，transcript 存任务 scratch/）。
