# 异常事件建模与重排触发 — Implementation Plan

## Execution Order

1. 09-16-exception-event-modeling — 先行。
   - execution：`EXCEPTION_TASK_EVENT` 常量；`record()` 链 EXCEPTION→PAUSED + payload
     `reasonCode`；命令端点 `POST /execute/event/exception/{taskId}`；直录路径接受 EXCEPTION；
     `event_type` 列注释更新。
   - master-data：`ResourceStatusUpdateApi`（api 模块）+ `/internal/master-data/resource-status`
     内部端点（校验 + 更新 + 同事务 bump）。
   - planning：`consumeResourceStatusChanged` 增加回写（Feign + 服务身份，fail-closed 不 ack）。
   - 测试：命令链（PAUSED + 级联同 PAUSE + reasonCode）、契约端点（校验/bump）、消费回写
     （成功/失败不 ack）；既有回归全绿。
2. 09-16-reschedule-trigger — 仅在 child-1 合入后开始。
   - planning：触发集合常量（DOWN/AVAILABLE）、pending 标记（Redis）、消费侧置标 + 提交、
     sweeper 排空；EXCEPTION 不触发。
   - 测试：触发/去抖/排空/不触发集合；与互斥并存的并发场景；既有回归全绿。
3. 父任务收尾：跨子任务端到端验证（异常上报 → 状态回写 → 自动重排 → DOWN 资源不被选中）、
   docs/TODO.md P1 行收尾、README 事件基线表更新（EXCEPTION + 触发语义）。

## Validation Commands

- `cd product-services && mvn -B -ntp clean test`（基准 231 全绿 + 新增用例，记录精确数字）
- 根仓库 `mvn -B -ntp -DskipTests compile`（单体回滚点 28/28）
- 端到端实测（child-2 末段）：起栈（用户许可窗口）→ DOWN 事件 → 观察回写 + 自动重排 +
  DOWN 资源未被选中 → transcript 存档

## Review Gates

- 既有四事件值与行为零回归；事件 schema 只加不改。
- 消费失败必须 fail-closed 不 ack（禁 catch-and-ack）；触发链不得绕过 scheduleAllAsync 互斥。
- 单体/根 pom/根 schema.sql/compose.dev.yml 零改动；DTO 无持久化注解；新代码构造器注入。
- 每子任务：实施 → trellis-check → 提交 → 归档；父任务收尾做跨子任务集成评审。

## Rollback Points

- child-1：消费者分支与命令端点独立回滚（新事件类型无人产生即零影响）。
- child-2：触发集合/标记逻辑独立回滚（回写保留，自动重排退化为人工触发）。
- 无 schema 破坏性变更（task_event/resource_status_event 列已在；master-data 无新表）。
