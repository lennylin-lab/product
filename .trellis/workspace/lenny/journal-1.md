# Journal - lenny (Part 1)

> AI development session journal
> Started: 2026-08-30

---



## Session 1: PPS排程P0改进：多资源排程与产品模具参数校验

**Date**: 2026-08-30
**Task**: PPS排程P0改进：多资源排程与产品模具参数校验
**Branch**: `master`

### Summary

完成多资源排程(task_assignment_resource)、任务依赖约束、A2 INJECT时长计算；模具参数校验收敛至产品创建/更新端点；移除PPS链条冗余校验。

### Git Commits

| Hash | Message |
|------|---------|
| `09b2030` | (see git log) |

### Status

[OK] **Completed**


## Session 2: PPS 工艺路线完整闭环

**Date**: 2026-09-06
**Task**: PPS 工艺路线完整闭环
**Branch**: `master`

### Summary

实现规则注册表、路线 CRUD API、产品绑定，以及 queue_policy/换型泛化排程扩展；测试通过并已归档 4 个 Trellis 任务。

### Git Commits

| Hash | Message |
|------|---------|
| `c849bca` | (see git log) |

### Status

[OK] **Completed**


## Session 3: PPS 工艺路线完整闭环

**Date**: 2026-09-06
**Task**: PPS 工艺路线完整闭环
**Branch**: `master`

### Summary

实现规则注册表、路线 CRUD API、产品绑定，以及 queue_policy/换型泛化排程扩展；测试通过并已归档 4 个 Trellis 任务。

### Git Commits

| Hash | Message |
|------|---------|
| `c849bca` | (see git log) |

### Status

[OK] **Completed**


## Session 4: 修复新环境引导链:schema.sql 补交与 README 刷新

**Date**: 2026-09-10
**Task**: 修复新环境引导链:schema.sql 补交与 README 刷新
**Branch**: `master`

### Summary

评审发现 .gitignore 的 *.sql 与 **/.env 规则导致新 clone 无法引导(schema.sql 从未入库、deploy/redis|elk/.env 缺失);从实体/Mapper XML/权限注解重建 schema.sql(32 表 + admin/admin123 种子,经 MySQL 8 实测导入验证,BCrypt 哈希经 bcryptjs 校验),补齐两个 deploy 独立 env,修正 ELK compose 残留绝对路径默认值,并刷新 README 进度、API 清单与数据库规范。

### Git Commits

| Hash | Message |
|------|---------|
| `3d26512` | (see git log) |
| `5462a99` | (see git log) |

### Status

[OK] **Completed**
