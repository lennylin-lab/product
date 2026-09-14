# ADR-0004: 跨服务事件一致性与异步消息（RabbitMQ + Outbox）

- 状态: Accepted
- 关联: design.md §4/§5、PRD R5/R9、implement.md Phase 5

## 背景

当前"执行 -> 状态刷新"为进程内同步链：`product-execute` 写 `task_event` 后同步联动 `operation_task`、`production_batch`、`order_line`、`customer_order` 状态。服务化后该链路横跨 Execution → Planning → Demand 三个边界。PRD R9 已锁定 RabbitMQ（不使用 RocketMQ/Kafka）；design.md 已决定默认不引入 Seata。

## 决策

### 1. 通信边界

- **同步（OpenFeign）**：仅用于必须立即得到结果的只读查询与轻量命令确认（如 Demand 保存前校验 product_id、Identity 权限查询兜底）。设置 connect/read 超时；写命令不自动重试；读请求仅限幂等且总超时受控的有限重试。
- **异步（RabbitMQ）**：一切跨服务状态联动。执行事件驱动任务/批次/订单状态推进全部改为事件最终一致。

### 2. 领域事件契约（v1）

envelope 固定字段：

```json
{
  "eventId": "UUID（全局唯一，幂等键）",
  "eventType": "task.status.changed（{domain}.{entity}.{action}）",
  "version": "envelope 契约版本，起始 1",
  "occurredAt": "ISO-8601",
  "producer": "product-execution",
  "aggregateId": "taskId / batchId / orderLineId",
  "correlationId": "透传自触发命令/上游事件的 traceId",
  "payload": "各事件自有字段，只包含本域已提交数据"
}
```

首批事件：`task.status.changed`（Execution→Planning）、`batch.progress.changed`（Planning→Demand）、`order_line.progress.changed`（Planning→Demand）、`resource.status.changed`（Execution→Planning，仅记录/告警用途）。

### 3. 可靠投递：事务内 Outbox

- 业务行与 outbox 行在同一本地 ACID 事务写入（outbox: `event_id, event_type, aggregate_id, payload, status, retry_count, next_retry_at`，归属各生产者服务的库）。
- 独立发布器轮询 outbox 投递 RabbitMQ，开启 publisher confirm；confirm 成功后才标记 `PUBLISHED`。投递失败按退避重试，超限告警并进入人工处理。
- 禁止"先发消息后提交事务"或"事务内直接 await 发送"。

### 4. 可靠消费：手动 ack + 幂等

- 消费者手动 ack；处理成功 ack，业务失败进入有限重试后路由死信交换机（DLX/DLQ），死信附原始 envelope 与失败原因。
- 以 `eventId` 为去重键记录消费流水（消费者本地库 `consumed_event` 表），重复投递直接 ack 跳过。
- 乱序处理：同聚合事件携带状态版本/时间戳，消费者按状态机单调性校验，过期事件记录后丢弃（不回滚已提交状态）；无法判定时进补偿队列。

### 5. 拓扑与配置约定

- exchange：`{domain}.events`（direct，持久化）；routing key = `eventType`；queue：`{consumer-service}.{event-domain}`，持久化。
- 声明由消费者代码管理；queue/DLQ 参数变更视为契约破坏，需升 `version` 并评审。

### 6. 对账与补偿

- 定时对账任务校验：订单行进度 = 批次聚合、批次状态 = 任务聚合、任务状态 = 事件流水，不一致写入补偿队列并告警；对账差异未清零不得视为链路健康。
- 人工重放工具：按 aggregateId/eventType 从 outbox 或 DLQ 重发（新 eventId、保留 correlationId）。

### 7. 分布式事务

默认不引入 Seata。仅当出现"无法通过边界重划或事件补偿达成、且必须强一致的短事务"时，单独立项评审。

## 备选方案

- Kafka/RocketMQ：被否决（R9；当前为可靠投递与路由诉求，无高吞吐/回放流需求）。
- 同步 Feign 链式刷新状态：被否决，级联失败与长事务风险，正是本次要消除的形态。

## 后果

- Phase 5 需建：outbox 表与发布器、消费幂等框架（可做成公共服务模块）、DLQ 与重放工具、对账任务。
- 排程输入一致性问题不在本 ADR 范围（属 Phase 4 版本化快照契约）。
