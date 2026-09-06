# PPS 工艺路线完整闭环

## Goal

将「自定义工序 → 产品工艺路线配置 → 任务生成 → 排程」打通为可在系统内维护、校验、执行的完整闭环，不再依赖手工写库。

## Background

P1 已将任务生成改为 `product_route` + `route_operation` 驱动，但存在缺口：

- 无工艺路线管理 API / 界面入口
- `eligible_resource_rule` / `std_time_model` 仅硬编码 3 种
- `queue_policy` 未接入排程
- 换型逻辑硬绑定 `op_code=SETUP`
- 未知工序编码可能导致空资源需求或错误 fallback

## Requirements（跨子任务）

### R1 工艺路线可维护

- 提供 REST API 创建、查询、更新、启用/停用产品工艺路线及工序明细。
- 每个产品同一时间仅一条启用路线；启用新路线时自动停用同产品旧路线。
- 路线保存时校验工序顺序、规则引用、工时模型引用合法性。

### R2 工序规则与工时模型可扩展

- 将 `eligible_resource_rule`、`std_time_model` 抽象为注册表，保存路线时拒绝未知引用。
- 保留现有三种标准规则/模型行为不变（SETUP / INJECT / POST）。
- 支持通过注册表扩展新规则类型（至少 1 个示例扩展点文档化）。

### R3 排程完整消费路线定义

- 任务生成与排程统一走注册表解析资源需求与标准工时。
- `queue_policy` 在机台/工位选择时生效（至少支持 `FIFO`、`SAME_MOLD_FIRST`）。
- 换型计算按「SETUP 类资源规则」触发，而非硬编码 `op_code`。
- 自定义 `op_code` 在配置了合法规则且资源能力已维护时可正常排程。

### R4 与产品创建协同（可选绑定）

- 产品详情可返回当前启用路线及工序列表。
- 创建/更新产品时可一并提交路线（与模具参数校验模式一致：入口集中校验）。

## Out of Scope

- 前端 UI 页面（本任务仅后端 API）
- 全新 `std_time_model` 的可视化公式编辑器
- 跨工厂多路线版本对比 / 审批流
- 外协工序（OFFLINE）资源模型

## Child Task Map

| 子任务 | 交付物 |
|--------|--------|
| `08-31-route-rule-registry` | 规则/模型注册表、校验器、单元测试 |
| `08-31-route-management-api` | 路线 CRUD API、产品绑定、集成校验 |
| `08-31-route-scheduling-ext` | queue_policy、换型泛化、排程/生成接入注册表 |

推荐实施顺序：rule-registry → management-api → scheduling-ext。

## Cross-Child Acceptance Criteria

- [ ] 可通过 API 为产品配置启用路线（含 ≥1 道工序），无需手工 SQL
- [ ] 保存路线时未知 `eligible_resource_rule` / `std_time_model` 被拒绝并返回明确错误
- [ ] 释放批次后按路线生成任务，资源需求与工时与路线定义一致
- [ ] 全量排程成功分配资源；`SAME_MOLD_FIRST` 在同模任务间优先同机台
- [ ] SETUP 类工序（`RULE_SETUP_MACHINE`）触发换型时间计算
- [ ] 产品 GET 接口返回启用路线（含工序明细）
- [ ] `product-pps` / `product-demand` 相关单元测试通过

## Notes

- 现有 P1 未提交改动（工艺路线读取、ChangeoverCalculator 等）视为本任务基线。
- 数据库表 `product_route` / `route_operation` 结构不变，优先复用现有实体。
