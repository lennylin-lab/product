# 异常事件与状态回写

## Goal

新增任务异常事件类型（EXCEPTION）并打通资源状态事件到 master-data 权威状态的回写，使异常/故障在任务与资源两侧真实可见。

## Requirements

- execution：`EXCEPTION_TASK_EVENT` 常量（只加）；异常上报命令端点 + 直录接受；`record()` 链 EXCEPTION→任务 PAUSED、事件行落 reason_code；`task.status.changed` payload 增加可选 reasonCode。
- master-data：资源状态更新契约（api 接口 + `/internal/master-data/resource-status` 内部端点），校验资源存在与状态合法，更新 + 同事务 bump。
- planning：`consumeResourceStatusChanged` 增加回写（Feign + 服务身份令牌，fail-closed 不 ack）。
- 消费侧 EXCEPTION→PAUSED 复用既有暂停级联（批次/订单行推进零新逻辑）。

## Acceptance Criteria

- [ ] 异常上报（端点 + 直录）使任务转 PAUSED、事件行含原因、payload 带 reasonCode；级联与 PAUSE 一致。
- [ ] 资源 DOWN 事件回写后 `resource.status=DOWN` 且版本计数递增；回写失败不 ack（重试/DLX 兜底）。
- [ ] 既有四事件、批次/订单推进回归全绿；事件 schema 只加不改。

## Dependencies

- 父任务：09-16-exception-events-rescheduling（KD1/KD3 已决）。
- 09-16-reschedule-trigger 依赖本任务（回写成功后才允许触发重排）。

## Out of Scope

- 重排触发链（child-2）；报工（KD4）；恢复工作流（走既有 RESUME）。
