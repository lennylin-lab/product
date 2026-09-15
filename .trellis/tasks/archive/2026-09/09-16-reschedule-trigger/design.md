# 09-16-reschedule-trigger — Design

> 架构权威来源：父任务 `09-16-exception-events-rescheduling/design.md`（数据流 3b/4、KD2）。
> child-1 已交付顺序契约：`consumeResourceStatusChanged` 中 `writeBackResourceStatus` 成功
> 之后、`record(APPLIED)` 之前是触发插入点（check 已确认插入点干净）。

## Scope

planning 侧的重排触发链：触发集合裁决、pending 标记、提交与去抖、sweeper 排空、端到端验证。
EXCEPTION 事件不触发（无容量变化）；事件建模与回写属 child-1（已交付）。

## Signatures

### 触发裁决（PlanningEventConsumerService.consumeResourceStatusChanged）

- 触发集合常量：`RESCHEDULE_TRIGGER_STATUSES = {DOWN, AVAILABLE}`（planning 侧常量类；
  DOWN=故障排除，AVAILABLE=恢复回归）。
- 位置：`writeBackResourceStatus(envelope)` 成功返回后、`record(APPLIED)` 之前：
  `toStatus ∈ 触发集合` → `rescheduleTrigger.requestReschedule()`；非触发集合 → 直接
  `record(APPLIED)`（只回写不触发，AC4）。
- 触发动作绝不让消费失败：互斥拒绝（「当前已有排程任务在执行」）是**预期分支**——吞掉并
  依赖标记持久化；仅记录日志。意外异常同样不传播（触发失败的兜底是标记 + sweeper，
  事件已成功回写，不应因触发层问题进入重试/DLX）。

### RescheduleTriggerService（新组件，构造器注入）

- `requestReschedule()`：SET pending 标记（Redis key `planning:reschedule:pending`，
  value = 置标时间戳，TTL 30min 防泄漏）→ 立即尝试 `scheduleJobService.scheduleAllAsync(...)`
  （默认参数，与人工入口同语义）；互斥 `ServiceException` → 吞掉（标记已在，sweeper 会
  排空）；提交成功 → 清除标记；其他异常 → 保留标记 + error 日志（sweeper 兜底）。
- sweeper 排空：`ScheduleJobTimeoutService` 既有 `@Scheduled`（默认 5min）节拍内追加一步——
  标记存在且当前无 RUNNING/PENDING schedule_job → 尝试提交，成功清标记；仍有任务在跑 →
  标记保留等下一拍（互斥兜底仍在）。
- 与 EXCEPTION 的隔离：触发只挂在 resource.status.changed 消费路径；task.status.changed
  （含 EXCEPTION）路径零改动。

## Validation & Error Matrix

| 条件 | 行为 |
|------|------|
| DOWN/AVAILABLE 事件回写成功 | 置标记 + 立即提交重排 |
| 提交遇互斥（已有排程在跑） | 吞掉，标记保留，sweeper 排空 |
| 提交成功 | 清标记 |
| MAINTENANCE/OFFSHIFT/BUSY 事件 | 只回写，不置标记不提交 |
| EXCEPTION 事件 | 不进入本链路（task.status.changed 消费路径不变） |
| 排程自身失败 | 既有 schedule_job FAILED + 超时清扫语义 |

## Tests Required

1. 触发裁决：DOWN/AVAILABLE → requestReschedule 调用；MAINTENANCE/OFFSHIFT/BUSY → 不调用。
2. 标记语义：互斥拒绝 → 标记保留 + 不抛出；提交成功 → 标记清除；其他异常 → 标记保留。
3. sweeper：标记存在且无运行任务 → 提交 + 清标记；有运行任务 → 保留；无标记 → 不动作。
4. 端到端（离线）：消费 DOWN 事件 → 回写（mock）→ 提交（mock）调用链按序发生。
5. 回归：child-1 全部消费测试、既有互斥/超时/sweeper 测试保持绿。

## Wrong vs Correct

#### Wrong
- 触发失败让事件进重试/DLX（回写已成功，事件不该因触发层重投——标记就是持久化）。
- 绕过 scheduleAllAsync 自起线程排程（绕过互斥/超时/审计）。
- 每个事件都无条件提交（风暴场景互斥拒绝日志爆炸）。

#### Correct
- 标记 + 单飞提交 + sweeper 兜底：任意事件数量收敛为至多一次在跑重排 + 一次补跑。
