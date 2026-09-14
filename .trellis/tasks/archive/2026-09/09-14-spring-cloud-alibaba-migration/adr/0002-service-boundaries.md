# ADR-0002: 服务边界与职责

- 状态: Accepted
- 关联: design.md §2 Target Services、PRD R2/R5

## 背景

当前 14 个 Maven 模块由 `product-server` 聚合为单进程，共享一个 `schema.sql`（31 张表）、共享 `product-domain` 实体与 `product-framework` 基础设施。已确认的跨边界耦合：

- `product-auth` 直接依赖 `product-system-api` 查询用户/角色/菜单/权限；
- `product-demand` 直接依赖 `product-pps` 维护产品工艺路线；
- `product-execute` 经进程内服务调用联动任务、批次、订单行、订单状态；
- 排程一次运行需读订单、产品工艺、资源日历、任务与派工多域数据。

## 决策

按业务能力与数据所有权划分 5 个自治服务 + 1 个网关（与 Maven 模块不一一对应）：

| 服务 | 职责 | 拥有数据 | 对应现有模块（迁移来源，非边界） |
| --- | --- | --- | --- |
| `product-gateway` | 唯一入口、路由、CORS、限流、JWT 前置校验、trace 注入 | 无业务表 | 新建 |
| `product-identity` | 登录、验证码、JWT 签发、用户/角色/菜单/字典/权限查询 | `sys_user`、`sys_role`、`sys_menu`、`sys_role_menu`、`sys_user_role`、`sys_dict_type`、`sys_dict_data` | product-auth、product-system、product-system-api |
| `product-master-data` | 产品、工艺路线、工序规则、资源、机台、模具、能力、日历、换型规则 | `product`、`product_mold_param`、`product_route`、`route_operation`、`resource`、`machine`、`mold`、`machine_mold_compatibility`、`resource_capability`、`calendar`、`changeover_rule` | product-master、product-pps 中工艺路线部分 |
| `product-demand` | 客户、订单、订单行及订单生命周期 | `customer`、`customer_order`、`order_line` | product-demand |
| `product-planning` | 批次、工序任务、资源需求、派工、排程任务与算法 | `production_batch`、`operation_task`、`task_dependency`、`task_resource_requirement`、`task_assignment`、`task_assignment_resource`、`schedule_job` | product-pps 主体 |
| `product-execution` | 任务/资源状态事件写入、现场追溯查询 | `task_event`、`resource_status_event` | product-execute |

边界规则：

1. 每个服务拥有独立 API contract 模块（`*-api`），契约 DTO 独立定义；禁止共享 MyBatis Entity，禁止跨服务读写他方表。
2. `product-common` 只保留无业务语义的技术基础（AjaxResult/TableDataInfo、工具类）；`product-domain` 拆解进各服务内部模型。
3. `product-become`（代码生成器）保留为开发期工具，不作为运行服务；`gen_table`/`gen_table_column` 随工具放在工具库。
4. 文件上传首版由网关转发至归属服务（`/common/upload` 维持现状语义）；若形成独立文件生命周期再拆文件服务。
5. 消除既有耦合的方向：demand 对产品的引用保存 `product_id` 并调用 Master Data 校验/批量查询；execute 的状态刷新改为事件驱动，Planning/Demand 各自消费；Identity 是权限唯一事实源。

## 备选方案

- 每个 Maven 模块一个服务：被否决，边界与数据所有权不吻合（工艺路线横跨 master/pps），且服务数翻倍无业务收益。
- 粗粒度 3 服务（管理/计划/执行）：被否决，Identity 独立性（认证安全边界）与执行事件闭环会被重新耦合。

## 后果

- 需要新建 6 个可部署单元并在 Phase 1 建骨架。
- 排程算法的数据获取必须改为批量契约 + 版本化快照（见 ADR-0004 与 implement.md Phase 4）。
- 跨服务引用由外键/关联查询改为业务 ID + 应用层校验（见 ADR-0005）。
