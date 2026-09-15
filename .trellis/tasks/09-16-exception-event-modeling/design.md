# 09-16-exception-event-modeling — Design

> 架构权威来源：父任务 `09-16-exception-events-rescheduling/design.md`（数据流 1–3a、契约、
> 失败矩阵）。KD1/KD3 已决：EXCEPTION → 任务复用 PAUSED；资源状态回写 master-data 权威状态。

## Scope

execution 的 EXCEPTION 事件建模（常量/命令端点/直录/record 链/payload）、master-data 资源状态
更新契约、planning 消费回写。重排触发属 `09-16-reschedule-trigger`，本任务不做。

## Signatures（全部增量，事件 schema 只加不改）

### execution
- `TaskEventConstants` 增加 `EXCEPTION_TASK_EVENT = "EXCEPTION"`（新常量，冻结四值不动）。
- `TaskEventServiceImpl.record()`：eventType=EXCEPTION → 目标状态 PAUSED（与 PAUSE 同款目标态），
  事件行落 `reason_code`/`remark`；outbox `task.status.changed` payload 增加可选 `reasonCode`
  （v1 只加不改）；发布后既有批次级联不变（PAUSED 目标态走既有路径）。
- 命令端点：`TaskEventController` 增加 `POST /execute/event/exception/{taskId}`
  （body: reasonCode 必填、remark 可选），语义对齐既有 start/pause/resume/complete 端点风格。
- 直录路径：`POST /execute/event` 接受 `eventType=EXCEPTION`（沿用 issue#4 后的直录校验：
  任务存在性 + eventTime 兜底）。
- `event_type` 列注释追加 EXCEPTION（仅注释）。

### master-data
- api 模块：`ResourceStatusUpdateApi`（Feign 接口，`contextId` 独立）+
  `ResourceStatusUpdateRequest { resourceId, toStatus, reasonCode? }`；DTO 无持久化注解。
- 内部端点：`POST /internal/master-data/resource-status`（挂 InternalMasterDataController 或
  同风格新内部控制器）：校验资源存在、toStatus ∈ AVAILABLE/BUSY/DOWN/MAINTENANCE/OFFSHIFT；
  更新 `resource.status` + 同事务 `MasterDataVersionService.bump()`；幂等（同状态重复回写
  成功返回，不重复 bump 或按现状语义——以实现取舍并记录）。
- 鉴权：与既有内部契约一致（JWT 本地验签；服务身份令牌经既有 identity 端点签发）。

### planning
- Feign 客户端接 `ResourceStatusUpdateApi`（服务身份令牌，复用 PlanningFeignAuthInterceptor/
  ServiceIdentityTokenProvider；超时 2s/3s，NEVER_RETRY——照抄 demand→master-data 既有配置）。
- `consumeResourceStatusChanged` 升级：幂等检查后调回写契约；**fail-closed——回写失败抛错不
  ack**（走既有重试/DLX/对账重放）；成功 → `consumedEventRecorder.record(APPLIED)`。
  触发重排不在本任务（child-2）。
- 回写与消费的顺序契约：回写成功是后续任何触发的前置（本任务仅回写）。

## Validation & Error Matrix

| 条件 | 行为 |
|------|------|
| EXCEPTION 上报（端点/直录），任务不存在 | 既有「任务不存在/没有可用资源」失败语义 |
| EXCEPTION 成功 | 任务 PAUSED + task_event 行含 reason_code + payload 带 reasonCode |
| 回写契约：资源不存在 / toStatus 非法 | master-data 拒绝（ServiceException 风格错误体），planning 不 ack |
| 回写 Feign 失败/超时 | planning 抛错不 ack → 重试 → DLX（既有语义），对账工具可重放 |
| 重复消费同一事件 | 幂等跳过（alreadyConsumed），不重复回写 |
| 既有四事件 | 行为零变化（record 链与级联回归） |

## Tests Required

1. execution：命令端点 EXCEPTION → PAUSED + reason_code 落行 + payload 带 reasonCode；
   直录 EXCEPTION；既有四事件回归。
2. master-data：契约端点校验（不存在/非法状态拒绝）、成功更新 + bump、幂等语义。
3. planning：消费回写成功（Feign mock 调用一次 + APPLIED）；回写失败不 ack（抛错）；
   重复事件幂等；既有 task.status.changed 消费回归。

## Wrong vs Correct

#### Wrong
- EXCEPTION 引入新任务状态值（KD1 否决）；或直接改 master_data_db（跨库禁止）。
- 回写失败吞掉后照常 ack（丢事件 = 丢状态回写，必须 fail-closed）。

#### Correct
- 新常量/新端点/新契约全增量；失败复用既有重试/DLX/对账设施；四事件与级联零回归。
