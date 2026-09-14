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


## Session 5: Spring Cloud Alibaba 微服务化改造：Phase 0–6 全程实施与验收
<!-- trellis-session: v=2 fp=64013aba5f02c507 -->

**Date**: 2026-09-15
**Task**: Spring Cloud Alibaba 微服务化改造：Phase 0–6 全程实施与验收
**Branch**: `master`

### Summary

完成单体到 SCA 微服务的七阶段迁移：版本基线与 ADR（JDK17/Boot3.5.16/SC2025.0.3/SCA2025.0.0.0/Nacos3.0.3）；平台骨架（网关+5 服务+公共库+CI）；Identity（RS256+JWKS、契约逐字节兼容）；主数据/需求域（demand→pps 解耦、Feign 批量契约、版本化快照）；排程域（37 单测全量迁移、对拍逐字段一致、漂移守卫/互斥/超时）；执行域与事件（Outbox/幂等/重试/DLX/对账重放，状态链事件化对拍 10/10）；统一切换验收（E2E 42 断言、安全/故障/容量、备份回滚演练、文档收口）。全程每阶段 trellis-check 独立校验并修复（拦下跨库 JOIN、虚报记录、测试红等 20+ 问题），最终 mvn clean verify 28/28 模块 243 用例全绿。遗留：推送前建议 squash 含演练 JWT 私钥的提交历史；CI 远端首跑待推送确认；生产切换决策待用户。

### Git Commits

| Hash | Message |
|------|---------|
| `8d4fb64` | docs(trellis): complete phase 0 architecture gate for sca migration |
| `40baafb` | feat(services): phase 1 platform skeleton for sca microservices |
| `8eff448` | feat(identity): phase 2 identity service and gateway security |
| `4ea022d` | feat(master-data,demand): phase 3 master data and order domain services |
| `1ea8ca5` | feat(planning): phase 4 planning domain and scheduling parity |
| `c4cea14` | feat(execution,messaging): phase 5 event consistency backbone |
| `bc0fa88` | feat(cutover): phase 6 unified cutover and acceptance |
| `a054770` | chore(security): remove drill JWT private keys from repo, ignore jwt-keys/ |

### Status

[OK] **Completed**
