# 异常事件建模与重排触发

## Goal

在 product-services 体系内建模任务异常事件、打通资源状态事件到资源权威状态的回写链路，并让资源故障类事件自动触发全量重排——使「任务执行异常 / 资源故障」反映到任务与资源状态上，且排程结果自动规避故障资源。

## Confirmed Facts（仓库证据，2026-09-16 调查）

- 任务事件类型值冻结为四种：START/PAUSE/RESUME/FINISH（`product-execution/common/constant/TaskEventConstants.java`）；任务状态 READY/SCHEDULED/RUNNING/PAUSED/DONE/CANCELLED（planning `StatusConstants.java:25-30`）。
- `task_event` 表已预留 `reason_code`/`remark`/`qty_good`/`qty_bad` 列（execution_schema.sql:38-50），异常建模无需改表。
- 资源状态事件骨架已在：`resource_status_event` 表（execution_schema.sql:56-68）+ `ResourceStatusEventServiceImpl.record()`（记录 + outbox 发布 `resource.status.changed`，同事务）+ 登记端点 `POST /internal/execution/resource-status-events`（InternalExecutionController.java:52）。**不驱动任何状态机**。
- `resource.status.changed` 的 payload schema v1 已定义（resourceId/fromStatus/toStatus/reasonCode/relatedTaskId/occurredEventId，只加不改）；planning 已消费但仅记录（PlanningEventConsumerService.java:86-110）。
- 资源状态枚举 AVAILABLE/BUSY/DOWN/MAINTENANCE/OFFSHIFT 已存在；资源权威状态归 master-data（ADR-0005），execution 不得直写 master_data_db。
- 排程入口只有全量重排 `scheduleAllAsync`（Redis 提交互斥 + 超时清扫 sweeper，Phase 4）；全量排程服务端 ~426ms（容量基准）。planning 已有服务身份链路（ServiceIdentityTokenProvider + PlanningFeignAuthInterceptor）。
- 事件 envelope v1 演进策略只加不改；Phase 5 已验证重复/乱序/延迟/死信/对账全套消费语义。
- 报工功能整体不存在（全仓库无实现）。

## Key Decisions（用户已决，2026-09-16）

- **KD1**：异常事件（新增 `EXCEPTION` 类型）后任务**复用既有 PAUSED 状态**，不新增状态值；原因落 `task_event.reason_code`/`remark`。理由：任务状态值被 demand 聚合/批次推进/前端消费（冻结语义），新增状态值连锁改动多域。
- **KD2**：重排触发为**事件驱动自动全量重排**——复用 `scheduleAllAsync` 互斥/超时/幂等，不做子集重排；触发即投递，重入经 pending 标记 + 互斥去抖，不丢重排。
- **KD3**：资源状态事件**回写 master-data 权威资源状态**（master-data 新增状态更新契约，planning 消费事件后经服务身份调用回写）；重排快照的 AVAILABLE 过滤自然排除 DOWN 资源。
- **KD4**：**报工失败排除**在本任务外（报工功能不存在，投机建模；将来建报工时复用 EXCEPTION 模式）。

## Requirements

- R1（child-1）— EXCEPTION 事件类型（只加不改）：execution 新增异常上报命令端点与直录接受，`record()` 链 EXCEPTION → 任务 PAUSED + 事件行落 `reason_code`，outbox 发布 `task.status.changed`（payload 增加可选 `reasonCode`，v1 兼容）；planning 消费侧 EXCEPTION→PAUSED 复用既有暂停级联（批次/订单行推进与 PAUSE 一致）。
- R2（child-1）— master-data 新增资源状态更新契约（内部端点 + api 模块接口）：校验资源存在与状态合法、更新 `resource.status`、同事务版本 bump（在跑排程经漂移守卫自然失败重排）。
- R3（child-1）— planning 消费 `resource.status.changed` 升级：回写权威状态（fail-closed：回写失败不 ack，走既有重试/DLX）。
- R4（child-2）— 重排触发链：消费回写成功后，`toStatus ∈ 触发集合（DOWN、AVAILABLE 恢复）` 的事件置 pending 标记并尝试 `scheduleAllAsync`；互斥拒绝时标记保留，由既有 sweeper 在空闲时排空；EXCEPTION 事件不触发重排（无容量变化，任务资源占用保持）。
- R5 — 既有四事件、批次/订单推进、资源状态事件表结构零回归；单体/根 pom/根 schema.sql/compose.dev.yml 零改动。

## Acceptance Criteria

- [ ] AC1 — 异常上报：`POST /execute/event/exception/{taskId}`（带 reasonCode）与直录 `eventType=EXCEPTION` 均使任务转 PAUSED、事件行含原因、`task.status.changed` payload 携带 `reasonCode`；planning 侧批次/订单行级联结果与 PAUSE 完全一致。
- [ ] AC2 — 资源 DOWN 回写：资源状态事件 `toStatus=DOWN` 经消费后，master_data_db `resource.status` 变为 DOWN 且版本计数递增；回写失败时事件不被 ack（重试/DLX 兜底）。
- [ ] AC3 — 自动重排：DOWN 事件回写成功后自动发起全量重排；重排期间并发事件经 pending 标记 + 互斥去抖，标记最终被排空（无丢失重排）；重排后 DOWN 资源不被选中（快照 AVAILABLE 过滤）。
- [ ] AC4 — 恢复事件（`toStatus=AVAILABLE`）同样触发重排；非触发集合状态（如 OFFSHIFT）只回写不重排。
- [ ] AC5 — 回归：既有四事件命令链、批次/订单推进、Phase 5 消费语义（重复/乱序/死信）测试全部保持绿；事件 schema 只加不改。
- [ ] AC6 — 单体/根 pom/根 schema.sql/compose.dev.yml 零改动；新增代码遵循 per-service recipe（构造器注入、离线测试、错误契约）。

## Out of Scope

- 报工功能与报工失败建模（KD4）。
- 子集/部分重排（仅全量，KD2）。
- 资源状态事件的维护端点 UI、告警通知渠道。
- EXCEPTION 的审批/恢复工作流（恢复走既有 RESUME 命令）。

## Task Map

- 09-16-exception-event-modeling — 第一子任务：EXCEPTION 事件（execution）+ 状态更新契约（master-data）+ planning 消费回写。
- 09-16-reschedule-trigger — 第二子任务：依赖第一子任务；重排触发集合、pending 标记、sweeper 排空与端到端验证。
