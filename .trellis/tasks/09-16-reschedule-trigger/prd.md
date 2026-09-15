# 异常驱动重排触发

## Goal

planning 消费资源状态事件、回写成功后按触发集合自动发起全量重排：pending 标记 + 提交互斥去抖 + sweeper 排空，保证不丢重排。

## Requirements

- 触发集合：`toStatus ∈ {DOWN, AVAILABLE(恢复)}`；其他状态只回写不触发；EXCEPTION 事件不触发（无容量变化）。
- 触发机制：置 Redis pending 标记 → 尝试 `scheduleAllAsync`；互斥拒绝时标记保留；既有 sweeper 节奏在无运行任务时排空标记重试（幂等）。
- 触发前序契约：回写成功是触发的前置（顺序契约见父 design）。

## Acceptance Criteria

- [ ] DOWN/恢复事件自动发起重排；并发场景经标记 + 互斥去抖，标记最终排空（无丢失重排）。
- [ ] 重排后 DOWN 资源不被选中（快照 AVAILABLE 过滤）。
- [ ] 非触发状态不重排；EXCEPTION 不触发；既有排程互斥/超时/清扫回归全绿。

## Dependencies

- 依赖 09-16-exception-event-modeling（回写链路与服务身份 Feign 已就绪）。

## Out of Scope

- 子集/部分重排；重排策略配置化；通知渠道。
