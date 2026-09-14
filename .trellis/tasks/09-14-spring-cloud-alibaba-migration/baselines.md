# Phase 0 基线冻结（外部 API / 数据库 / 核心 E2E）

> 冻结日期：2026-09-14。本文档记录统一切换前**单体现状**的外部 API、数据库与核心 E2E 用例，作为微服务化后的兼容性比对基线。未经评审不得修改；差异必须在 Phase 6 验收时逐条核对并说明。

## 1. 外部 API 基线

### 1.1 服务器与协议约定

- 启动入口：`product-server` 单进程，`server.port=8081`，`context-path=/`。
- 响应契约：单体结果 `AjaxResult`（`{code, msg, data}`，业务错误也是 HTTP 200 + code 500/自定义码）；分页 `TableDataInfo`（`{code, msg, rows, total}`）。错误转换由 `product-framework` 的 `GlobalExceptionHandler` 统一完成（ServiceException → 200 + code，校验异常 → 200 + 首条错误信息，其余 → 200 + 通用错误）。
- 认证：JWT，header `Authorization: Bearer <token>`，TTL 300 分钟；匿名端点 `/login`、`/register`、`/captchaImage`、`@Anonymous` 注解端点、静态资源 `GET /`、`/*.html`、`/**.css`、`/**.js`、`/profile/**`、`/druid/**`、swagger 相关。
- 权限串格式：`{module}:{resource}:{action}`；仅 system 管理模块大部分启用 `@PreAuthorize`，业务域（pps/demand/master/execute）当前仅要求登录（quality-guidelines 记录的现状，冻结为事实）。
- 分页：`PageUtils.buildPage()` + `getDataTable(page)`；Excel 导入导出为每资源 `/export`、`/importTemplate`、`/importData`。

### 1.2 端点清单（现状事实）

| 前缀 | 资源 | 标准端点形态 | 归属（目标服务） |
| --- | --- | --- | --- |
| `/login` | 登录 | `POST /login`、`GET /getInfo`、`GET /getRouters` | identity |
| `/captchaImage` | 验证码 | `GET /captchaImage` | identity |
| `/system/user` | 用户 | 标准 CRUD + `list/export/import*/{ids}` + 权限注解（当前部分被注释） | identity |
| `/system/menu` | 菜单 | 标准 CRUD + `treeselect/roleMenuTreeselect/{roleId}` | identity |
| `/system/dict/type`、`/system/dict/data` | 字典 | 标准 CRUD + `optionselect` | identity |
| `/system/user/profile` | 个人资料 | `GET`、`PUT`、`PUT /updatePwd`、`POST /avatar` | identity |
| `/demand/customer` | 客户 | 标准 CRUD | demand |
| `/demand/product` | 产品 | 标准 CRUD | master-data（路由保持 `/demand/product`） |
| `/demand/order` | 订单 | 标准 CRUD + 订单生命周期动作 | demand |
| `/demand/orderLine` | 订单行 | 标准 CRUD | demand |
| `/master/calendar` | 日历 | 标准 CRUD | master-data |
| `/master/resource/machine` | 机台 | 标准 CRUD | master-data |
| `/pps/product-route` | 工艺路线 | 标准 CRUD（含激活单路线约束） | master-data（路由保持 `/pps/product-route`） |
| `/pps/batch` | 批次 | 标准 CRUD | planning |
| `/pps/task` | 工序任务 | 标准 CRUD | planning |
| `/pps/assignment` | 任务分配 | 标准 CRUD | planning |
| `/execute/event` | 任务事件 | 标准 CRUD + 事件登记与状态联动（服务化后改为事件链路） | execution |
| `/common` | 文件 | `POST /common/upload`；下载走 `/profile/**` 静态映射 | 网关转发至归属服务 |

注：`POST /logout` 在 `SysLoginController` javadoc 中声明但**未实现**（现状为客户端删除 token）。`/register` 在 permitAll 列表但无对应实现。两者冻结为"不存在"，后续如补齐属新增功能。

### 1.3 统一切换兼容承诺

- Gateway 保持上表路径与响应结构不变；`AjaxResult`/`TableDataInfo` 信封不变。
- 已识别的允许差异：`/druid/**` 监控台不再暴露；静态 `/profile/**` 文件访问改为网关转发或对象存储（如实现，属显式破坏性变更，需在契约测试中记录）。
- swagger（springdoc）改为每服务 `/v3/api-docs` + 网关聚合，路径差异显式记录。

## 2. 数据库基线（schema.sql 现状）

- 单库（开发库 `product`），MySQL 8.4，utf8mb4/utf8mb4_unicode_ci，InnoDB；DDL 无外键约束（全部为应用层逻辑关联）。
- 共 32 张业务表 + 种子数据（admin/admin123、2 角色、系统/业务菜单、2 字典类型 5 条字典数据）。

### 2.1 表清单与所有权映射

| 所有权 | 表 |
| --- | --- |
| identity | `sys_user`、`sys_role`、`sys_menu`、`sys_role_menu`、`sys_user_role`、`sys_dict_type`、`sys_dict_data` |
| master-data | `product`、`product_mold_param`、`product_route`、`route_operation`、`resource`、`machine`、`mold`、`machine_mold_compatibility`、`resource_capability`、`calendar`、`changeover_rule` |
| demand | `customer`、`customer_order`、`order_line` |
| planning | `production_batch`、`operation_task`、`task_dependency`、`task_resource_requirement`、`task_assignment`、`task_assignment_resource`、`schedule_job` |
| execution | `task_event`、`resource_status_event` |
| become-tool | `gen_table`、`gen_table_column` |

### 2.2 需要保留语义的结构点

- `product_route.active_flag` 生成列 + 唯一键 `uk_product_route_active`：单产品仅一条活跃路线（迁移时原样保留）。
- `customer_order`/`order_line`、`production_batch`/`operation_task` 状态枚举值（`NEW/CONFIRMED/IN_PRODUCTION/DONE`、`PLANNED/RELEASED/IN_PROCESS/DONE`、`READY/SCHEDULED/RUNNING/PAUSED/DONE/CANCELLED`）为 E2E 断言依据。
- 主键策略：显式 ID（订单/批次/任务/事件等由应用生成）与 `AUTO_INCREMENT` 并存，迁移脚本保留各表 AUTO 起点与种子 ID。
- 状态字段全部为 `VARCHAR` + 注释枚举，无 DB 级 CHECK；状态机合法性由应用保证（事件链路改造时沿用）。

## 3. 核心 E2E 用例清单（统一切换验收从 Gateway 入口执行）

### 3.1 认证与权限（identity/gateway）

1. 登录成功：`POST /login`（admin + 验证码旁路/固定码）返回 token；`GET /getInfo` 返回用户+权限；`GET /getRouters` 返回菜单树。
2. 匿名访问：`/captchaImage` 可访问；无 token 访问业务接口被拒（401 语义与现状一致）。
3. 伪造/过期 token 被拒；退出后（前端删除）再访问被拒。
4. 越权校验：无权限用户访问 system 管理接口被 403/权限不足拒绝（现状注解范围为准）。
5. 用户/角色/菜单/字典管理 CRUD + 分页 + 导出。

### 3.2 订单 -> 批次 -> 排程 -> 执行 -> 完工 主链路

6. 主数据准备：创建产品、工艺路线（含三道工序 SETUP/INJECT/POST_QC_PUTAWAY）、机台/模具/兼容性/日历；产品路线激活唯一性生效。
7. 订单创建：创建客户与订单 + 订单行（产品、数量、交期、优先级），订单确认。
8. 拆批/生成批次：订单行生成生产批次与工序任务，任务依赖与资源需求正确。
9. 排程执行：触发排程任务（schedule_job），任务/批次获得分配（机台、计划起止、资源顺序），甘特/分配查询一致；重复排程幂等语义保持。
10. 执行上报：任务 START（占用资源）→ 暂停 → 恢复 → FINISH（良品/不良数量）逐事件生效；资源状态事件联动。
11. 完工闭环：全部工序完成后批次 DONE，订单行/订单进度更新至 IN_PRODUCTION → DONE；各域状态经事件最终一致并对账无差异。

### 3.3 横切用例（gateway/治理/一致性）

12. 文件上传：`POST /common/upload` 成功且下载路径可访问。
13. 导入导出：至少一个资源（如订单或产品）Excel 导入/导出成功。
14. 限流降级：网关/服务 Sentinel 规则触发时返回统一错误体，不破坏认证语义。
15. 消息可靠性（若 Phase 5 已实施）：重复事件不产生重复状态变更；MQ 短暂不可用后自动恢复消费；对账任务无差异。
16. 可观测性：一次完整主链路调用在日志（ELK）与 trace 中可按 correlationId/traceId 串联；各服务健康检查通过。

### 3.4 验收口径

- 上述用例在**单体现状**先行人工/脚本记录预期结果（响应结构、状态值），微服务切换后逐条比对；路径不变、信封不变、状态机终态一致为通过标准。
- 显式破坏性变更（如 swagger 聚合路径、/profile 静态访问方式）单独列表，不混入回归比对。
