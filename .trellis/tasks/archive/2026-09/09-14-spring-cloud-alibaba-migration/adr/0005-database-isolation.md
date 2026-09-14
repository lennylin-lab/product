# ADR-0005: 数据库隔离（database per service）

- 状态: Accepted
- 关联: design.md §3/§5、PRD R4、implement.md Phase 2–5

## 背景（现状证据）

单一 MySQL 库，全部 32 张业务表在根目录 `schema.sql`：系统 7 张（`sys_user`、`sys_role`、`sys_menu`、`sys_role_menu`、`sys_user_role`、`sys_dict_type`、`sys_dict_data`）、代码生成 2 张（`gen_table`、`gen_table_column`）、主数据 11 张、需求 3 张、排程 7 张、执行 2 张。DDL 无外键约束（关联均为逻辑关联）；`product_route` 使用生成列 `active_flag` 保证单产品单活跃路线；种子数据含 admin 账号、角色、菜单、字典。

## 决策

### 1. 隔离粒度：database per service

共享一个 MySQL 8.4 实例（开发期成本考虑），但**每个服务独立 database + 独立最小权限账号**；服务账号只能访问自己的 database。目标态禁止跨 database join、跨库写入、共享只读账号。

| Database | 归属服务 | 表 |
| --- | --- | --- |
| `identity_db` | product-identity | `sys_user`、`sys_role`、`sys_menu`、`sys_role_menu`、`sys_user_role`、`sys_dict_type`、`sys_dict_data` |
| `master_data_db` | product-master-data | `product`、`product_mold_param`、`product_route`、`route_operation`、`resource`、`machine`、`mold`、`machine_mold_compatibility`、`resource_capability`、`calendar`、`changeover_rule` |
| `demand_db` | product-demand | `customer`、`customer_order`、`order_line` |
| `planning_db` | product-planning | `production_batch`、`operation_task`、`task_dependency`、`task_resource_requirement`、`task_assignment`、`task_assignment_resource`、`schedule_job` |
| `execution_db` | product-execution | `task_event`、`resource_status_event` |
| `become_tool_db` | product-become（开发期工具） | `gen_table`、`gen_table_column` |

每个服务另建自己的 outbox / 消费去重表（ADR-0004），归属服务自身 database。

### 2. 跨服务引用规则

- 表间逻辑关联只保留在所有权内部（如 `order_line.order_id` → `customer_order`）。
- 跨所有权引用降级为业务 ID + 应用层校验：`order_line.product_id`、`task_assignment.machine_id`、`operation_task` 与 `resource` 的引用等，由服务调用契约（批量查询/存在性校验）保证。
- 不在 DDL 中重建跨库外键；不存在"共享只读从库"。

### 3. 迁移路径（一次性切换，PRD R6）

1. 从 `schema.sql` 按上表拆分为每服务独立初始化脚本（Phase 2 起随服务建立，Phase 6 定稿）。
2. 种子数据随所有权迁移：admin 账号、角色、菜单、字典 → `identity_db`；无业务种子数据的服务保持空库初始化。
3. 特殊对象保持语义：`product_route.active_flag` 生成列随表迁入 `master_data_db`；`AUTO_INCREMENT` 起点保持脚本原值。
4. 切换前：离线备份旧库 → 执行服务库初始化脚本 → 数据校验（行数 + 关键聚合一致性）→ 校验失败回滚为恢复旧库与旧构建（无运行期双写/灰度）。

### 4. 约束检查（Review Gate 落地）

- 每服务数据源 URL 指向唯一 database；账号权限 `SELECT/INSERT/UPDATE/DELETE` 仅限本库。
- 代码评审禁止：跨库表名出现在 Mapper/XML、跨服务 Entity import。
- 缓存（Redis）同步隔离：每服务独立 key prefix（`identity:`、`planning:`…），缓存不作为跨服务事实源。

## 备选方案

- 单库 + schema 前缀/逻辑隔离：被否决，无法做账号级强制隔离，约束只停留在纪律层面（PRD R4 要求禁止跨服务读写他方表）。
- 独立 MySQL 实例 per service：记录为后续选项，未上线阶段无运维必要。

## 后果

- 所有原本跨域的 join（如排程读订单/工艺、执行读订单）必须改为 API 契约或事件同步，工作量集中在 Phase 3–5。
- `product-domain` 共享实体按表所有权拆散，禁止任何服务 import 他服务持久化模型。
