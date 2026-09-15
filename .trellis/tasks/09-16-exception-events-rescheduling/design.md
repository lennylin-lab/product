# 异常事件建模与重排触发 — Design

## Architecture and Boundaries

- **execution = 事件生产者**：异常上报（命令端点 + 直录）落 `task_event` 行并经 outbox 发布
  `task.status.changed`（payload 增加可选 `reasonCode`）；资源状态事件登记沿用既有
  `POST /internal/execution/resource-status-events` → `resource.status.changed`。
- **planning = 消费编排者**：消费 `task.status.changed`（EXCEPTION → PAUSED，复用既有暂停级联）
  与 `resource.status.changed`（回写权威状态 + 触发集合裁决 + 重排触发）。
- **master-data = 资源权威状态所有者**：新增内部状态更新契约；回写更新 `resource.status` 并
  同事务 bump 版本计数（在跑排程经漂移守卫失败，配合重排链自然收敛）。
- 跨服务方向：planning → master-data（状态回写，服务身份令牌，复用 Phase 4 链路）；
  与 ADR-0005 所有权一致，无新增跨库访问。

## Data Flow

1. 异常上报：`POST /execute/event/exception/{taskId}`（body: reasonCode/remark）或直录
   `eventType=EXCEPTION` → `record()`：任务转 PAUSED（同 PAUSE 的目标状态语义）→ 事件行
   （reason_code）→ outbox `task.status.changed`（payload: targetStatus=PAUSED + reasonCode）。
2. planning 消费 task.status.changed：`EXCEPTION` 加入既有 eventType→targetStatus 冻结映射的
   增量分支（EXCEPTION→PAUSED）；批次/订单行级联走既有 PAUSED 路径，零新逻辑。
3. 资源状态事件：`POST /internal/execution/resource-status-events` → outbox
   `resource.status.changed` → planning 消费：
   a. 回写：调 master-data `ResourceStatusUpdateApi`（服务身份令牌）更新 `resource.status`；
      失败 → 抛错不 ack（既有重试/DLX 兜底），顺序契约 = 回写成功后才允许触发重排。
   b. 触发裁决：`toStatus ∈ {DOWN, AVAILABLE}`（DOWN 故障、AVAILABLE 恢复）→ 置 pending
      标记并尝试 `scheduleAllAsync`；其他状态（MAINTENANCE/OFFSHIFT/BUSY）只回写不触发。
4. 重排执行：既有互斥/超时/sweeper 机制；pending 标记在提交被互斥拒绝时保留，由
   `ScheduleJobTimeoutService` 既有清扫节奏在无运行任务时排空重试（幂等，不丢重排）。

## Contracts（全部只加不改）

- `TaskEventConstants` 增加 `EXCEPTION_TASK_EVENT = "EXCEPTION"`（新常量，四值不动）。
- `task.status.changed` payload 增加可选 `reasonCode`（v1 只加不改；消费方按需读取）。
- master-data 新增 `ResourceStatusUpdateApi`（product-master-data-api）：POST
  `/internal/master-data/resource-status`，body {resourceId, toStatus, reasonCode?}；
  校验资源存在 + toStatus ∈ 枚举；更新 + 同事务 bump。planning 经 Feign + 服务身份调用
  （网关 `/internal/**` 显式拒绝已覆盖，仅服务间可达）。
- planning 内部：pending 标记用 Redis key（`planning:reschedule:pending`，TTL 有界），
  写标 + 提交 + sweeper 排空三步均为幂等操作。

## Compatibility / Failure

- 既有四事件、`resource.status.changed` 无消费方依赖的行为（记录）、批次/订单级联、
  Phase 5 消费语义（重复/乱序/DLX/对账）全部零回归；回滚 = 消费者对新类型/触发集合的
  分支退化为记录（事件行仍在，无 schema 反向迁移）。
- 失败矩阵：回写 Feign 失败/超时 → fail-closed 不 ack（重试至 DLX，对账工具可重放）；
  回写成功但提交排程失败（互斥）→ 标记保留由 sweeper 排空；排程自身失败 → 既有
  schedule_job FAILED 语义与超时清扫。

## Risks

- 事件风暴：批量资源同时 DOWN 会连发触发——pending 标记 + 互斥天然去抖，重排本身 ~426ms。
- EXCEPTION→PAUSED 与人工 PAUSE 在任务列表不可区分（KD1 已接受的取舍），区分靠事件行与
  reasonCode。
- master-data 状态更新端点暴露面：仅 /internal/**（网关拒绝 + 服务身份令牌 + JWT 三层，
  与既有内部契约同水位）。
