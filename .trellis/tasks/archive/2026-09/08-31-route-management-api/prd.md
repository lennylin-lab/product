# 工艺路线管理 API

## Goal

提供 REST API 维护产品工艺路线，并与产品创建/查询打通，使路线可在系统内配置而无需手工 SQL。

## Requirements

### R1 ProductRouteService

- 创建路线：写入 `product_route` + 批量 `route_operation`（同事务）
- 更新路线：更新头信息 + 全量替换工序（delete by route_id + re-insert）
- 查询：按 `routeId` 或 `productId` 返回路线含有序工序
- 启用：同产品其他路线 `is_active=0`，目标路线 `is_active=1`
- 删除：仅当路线未启用，或启用但无 READY/SCHEDULED/IN_PROCESS 关联任务

### R2 ProductRouteController

- 路径 `/pps/product-route`，遵循现有 `BaseController` + `AjaxResult` / `TableDataInfo` 约定
- CRUD + activate 端点（见父任务 design.md）

### R3 保存时校验

- 调用 `RouteOperationValidator`（依赖子任务 route-rule-registry）
- 产品存在性校验（`product_id` 有效）

### R4 产品端协同

- `Product` 实体增加非持久字段 `activeRoute`（`ProductRoute` 含 `operations`）
- `selectProductByProductId` 加载启用路线
- `insertProduct` / `updateProduct` 接受可选 `activeRoute`：
  - 有则创建/更新并自动启用
  - 无则不影响现有路线

## Out of Scope

- 前端页面
- 路线版本 diff / 历史追溯 UI
- queue_policy 排程行为（scheduling-ext）

## Acceptance Criteria

- [ ] CRUD + activate API 可用（Postman/测试覆盖）
- [ ] 同产品仅一条启用路线
- [ ] 非法 rule/model 保存被拒绝
- [ ] 产品 GET 返回 `activeRoute`
- [ ] 产品 POST 可一并提交路线
- [ ] `ProductRouteServiceTest` 或 Controller 层测试通过

## Depends On

`08-31-route-rule-registry` 必须先完成（校验器 + registry）。

## Parent Link

父任务 `08-31-pps-product-route-closed-loop` — R1、R4。
