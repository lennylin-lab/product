# 排程引擎工艺路线扩展

## Goal

让排程与任务生成完整消费路线定义：queue_policy、换型泛化、自定义 op_code 能力校验。

## Requirements

### R1 queue_policy 接入

- 任务生成时将 `route_operation.queue_policy` 写入 `OperationTask`（新增持久或 transient 字段；优先持久字段 `queue_policy`）
- `TaskSchedulingCalculator.chooseBestMachine` / 工位选择参考 task 的 queue_policy
- 实现 `FIFO`（默认）、`SAME_MOLD_FIRST`（同模优先同机台）
- `EDD` 与全局 `SchedulingStrategy.DUE_DATE_PRIORITY` 对齐（inherit，不重复实现）

### R2 换型逻辑泛化

- 移除硬编码 `"SETUP".equals(task.getOpCode())`
- 当任务对应规则的 `triggersChangeover()==true` 时计算换型（即 `RULE_SETUP_MACHINE`）
- 行为与 P1 ChangeoverCalculator 一致

### R3 自定义 op_code

- 资源需求 `capabilityCode` 使用路线定义的 `op_code`
- 排程 `resourceSupportsTaskCapability` 继续按 `op_code` + `product_id` 匹配
- 任务生成时若 rule 产生空需求，validator 已在 API 层拦截；生成阶段 double-check 并 batch 报错

### R4 路线元数据在排程时可解析

- `TaskSchedulingQueryService` 批量加载 task → route_operation 映射（batch → product → active route → op by sequence/op_code）
- 或依赖 task 上已持久化的 `queue_policy` + 规则推断（简化方案）

## Out of Scope

- 新 queue_policy 类型 beyond FIFO/SAME_MOLD_FIRST/EDD
- 可视化排程甘特图

## Acceptance Criteria

- [ ] `SAME_MOLD_FIRST` 测试：同模两任务优先同一机台
- [ ] 换型测试：非 `SETUP` op_code 但 `RULE_SETUP_MACHINE` 仍触发换型
- [ ] 自定义 op_code + 合法 rule 可完成工位/机台排程（测试用例）
- [ ] 现有 `TaskSchedulingCalculatorTest` 用例不退化
- [ ] `mvn -pl product-pps test` 通过

## Depends On

- `08-31-route-rule-registry`（换型推断、资源规则）
- `08-31-route-management-api`（可选；可用测试 fixture 直接写库）

## Parent Link

父任务 `08-31-pps-product-route-closed-loop` — R3。
