# 工序规则与工时模型注册表

## Goal

将 `eligible_resource_rule` 与 `std_time_model` 从硬编码 switch 抽象为可注册、可校验的规则引擎，为路线 API 与排程提供统一解析入口。

## Requirements

### R1 资源规则注册表

- 定义 `RouteEligibleResourceRule` 接口：`code()`、`buildRequirements()`、`triggersChangeover()`、`requiresMachine()`、`requiresWorkstation()`。
- 注册并实现现有三种规则，行为与 P1 `OperationResourceRequirementBuilder` 一致。
- `RouteRuleRegistry` 提供 `findRule(String)`、`requireRule(String)`、`allRuleCodes()`。

### R2 工时模型注册表

- 定义 `RouteStdTimeModel` 接口与 `RouteDurationContext`（batch_qty, product_id, mold_param 等）。
- 注册并实现 `TM_INJECT_A2`、`TM_SETUP_BASE`、`TM_POST_UNIT`，行为与 P1 `resolveStdDurationMin` 一致。
- `RouteRuleRegistry`（或 sibling `RouteTimeModelRegistry`）提供 `findModel` / `requireModel` / `allModelCodes()`。

### R3 路线工序校验器

- `RouteOperationValidator.validate(List<RouteOperation>)`：
  - 工序非空；`sequence` 唯一且 > 0；`op_code` 非空
  - `eligible_resource_rule`、`std_time_model` 必须在注册表中
  - `queue_policy` 允许值：`FIFO`、`SAME_MOLD_FIRST`、`EDD`（非法值拒绝）
- 校验失败抛 `ServiceException`，消息含字段名与合法值提示。

### R4 重构接入点（本阶段仅改调用方签名，完整排程接入在 scheduling-ext）

- `OperationResourceRequirementBuilder` 改为委托 registry（保持 public API 不变）。
- `OperationTaskServiceImpl.resolveStdDurationMin` 改为委托 registry。

## Out of Scope

- 管理 API（下一子任务）
- `queue_policy` 排程逻辑（scheduling-ext）
- 数据库配置表驱动规则（代码注册即可）

## Acceptance Criteria

- [ ] 三种既有 rule/model 注册并通过单元测试
- [ ] 未知 rule/model 在 validator 中被拒绝
- [ ] `OperationResourceRequirementBuilderTest` 仍通过
- [ ] 新增 `RouteRuleRegistryTest` / `RouteOperationValidatorTest`
- [ ] `mvn -pl product-pps test` 通过

## Depends On

无（首个子任务）。

## Parent Link

父任务 `08-31-pps-product-route-closed-loop` — 提供 R2 基础能力。
