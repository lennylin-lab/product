# 09-16-exception-event-modeling — Implementation Plan

> 阅读顺序：implement.jsonl → 父任务 prd/design（KD1/KD3、数据流 1–3a、失败矩阵）→ 本任务
> design.md → `TaskEventServiceImpl.record()`、`PlanningEventConsumerService`、
> `InternalMasterDataController` 实际代码 → microservices-platform.md / error-handling.md。

## Checklist（按序执行）

- [x] 1. execution：`EXCEPTION_TASK_EVENT` 常量；`record()` 链 EXCEPTION→PAUSED +
  payload `reasonCode`（可选字段，只加）；`event_type` 列注释。
- [x] 2. execution：命令端点 `POST /execute/event/exception/{taskId}`（reasonCode 必填校验）；
  直录路径接受 EXCEPTION（沿用既有直录校验）。
- [x] 3. master-data：`ResourceStatusUpdateApi` + 请求 DTO（api 模块，无持久化注解）；
  `/internal/master-data/resource-status` 内部端点（存在性/合法性校验、更新、同事务 bump、
  幂等语义取舍并记录）。
- [x] 4. planning：Feign 客户端（服务身份令牌、2s/3s、NEVER_RETRY）+
  `consumeResourceStatusChanged` 回写（fail-closed 不 ack；幂等检查在前）。
- [x] 5. 测试（design.md Tests Required 三组全项）+ 既有回归（四事件、消费语义、
  Phase 5 套件）全绿。
- [x] 6. 文档：`docs/TODO.md` P1 行进度；`product-services/README.md` 事件基线表
  （EXCEPTION + 回写链路）；EventTypes javadoc payload 说明。

## Validation Commands

- `cd product-services && mvn -B -ntp clean test`（基准 231 全绿 + 新增用例，记录精确数字）
- 根仓库 `mvn -B -ntp -DskipTests compile`（单体回滚点 28/28）

## Review Gates

- 冻结四值与既有四事件行为零变化；事件 schema 只加不改。
- 回写失败必须抛错不 ack；禁止吞错 ack；禁止跨库直写 master_data_db。
- 单体/根 pom/根 schema.sql/compose.dev.yml 零改动；新代码构造器注入；测试离线可跑。
- 勾选后交 trellis-check 校验，再进入 09-16-reschedule-trigger。

## Rollback Points

- 全部增量：回滚 = 消费者回写分支与端点下线，事件行与四事件语义不受影响；无 schema 迁移。

## 执行记录（2026-09-16，trellis-implement）

### Clean-run 证据（来自本次 `product-services && mvn -B -ntp clean test`，BUILD SUCCESS，~1:02 min）

- 总计 **248 tests，Failures 0，Errors 0，Skipped 0**（基准 231 → +17 新增）。
- 分模块（本轮 surefire 汇总）：
  cloud-common 21 / cloud-security 11 / cloud-messaging 8 / master-data-api 0 / demand-api 0 /
  planning-api 0 / gateway 17 / identity 31 / master-data **20**（+5）/ demand-service 27 /
  planning **90**（+6）/ execution **23**（+6）。
- 新增用例：execution `TaskEventServiceImplTest` 11→17（+6：EXCEPTION 命令链/空白原因码拒绝/
  任务不存在/信封 reasonCode 可选装配/四事件 payload 无 reasonCode 键回归/直录 EXCEPTION 接受）；
  master-data `ResourceStatusUpdateServiceTest` 新类 5 条（缺资源ID拒绝、非法状态拒绝、
  资源不存在拒绝、更新+bump、同状态幂等）；
  planning `PlanningEventConsumerServiceTest` 6→12（+6：回写成功 APPLIED/Feign 失败不 ack/
  提供方拒绝不 ack/重复事件跳过回写/payload 缺字段拒绝/EXCEPTION 复用 PAUSED 落库路径）。
- 根仓库 `mvn -B -ntp -DskipTests compile`：**28/28 SUCCESS**。

### 关键实现取舍（已记录）

1. **payload reasonCode 只在非 null 时写入**（LinkedHashMap 不放 null 键）：既有四事件信封
   逐字节不变（`frozenFourEventsShouldNotCarryReasonCodeKeyInPayload` 回归钉住），v1 只加不改。
2. **master-data 幂等语义**：同状态重复回写静默成功且**不重复 bump**——版本计数职责是排程
   快照漂移守卫，仅主数据语义变化才应使在跑排程失效；状态变化才更新 + 同事务 bump。
3. **planning fail-closed 双通道**：Feign 异常直接上抛；提供方业务拒绝走既有错误契约
   （200 + 错误体 → 反序列化后 `resourceId` 缺省）→ 响应校验不过同样抛错不 ack
   （`resourceEventProviderRejectionShouldThrowWithoutAck` 钉住）。
4. `changeTaskStateAndRecordEvent` / `publishTaskStatusChanged` / `buildTaskStatusEnvelope`
   均以重载扩展（3 参→5 参 / 5 参→6 参），四事件路径委托传 null，行为零变化；测试桩同步改覆写
   6 参发布方法，原 11 条 execution 断言未改动。
5. 消费侧 EXCEPTION 无需改动：payload targetStatus=PAUSED 直接走既有无条件映射 + 批次级联
   （`exceptionTaskEventShouldReusePauseApplyPath` 用 mockStatic Db 证明落库路径复用）。

### 偏差

- 无实质偏差。补充说明：直录路径（`POST /execute/event`）本就无 eventType 白名单，
  EXCEPTION 无需改动即被接受（沿用 issue#4 校验），以测试钉住；`docs/TODO.md` 除 P1 行外
  同步更新了「当前状态 product-execution 行」与资源状态事件链路描述（同属进度记录，非代码）。
