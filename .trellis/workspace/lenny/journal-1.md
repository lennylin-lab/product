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
| `3544a1b` | feat(cutover): phase 6 unified cutover and acceptance |
| `0ecd860` | chore(security): remove drill JWT private keys from repo, ignore jwt-keys/ |

### Status

[OK] **Completed**


## Session 6: 修复远程 issue #6/#7/#8（网关夹具路由/机台类型过滤/删除机台事务性）
<!-- trellis-session: v=2 fp=1cde4fd2f4e3dba6 -->

**Date**: 2026-09-18
**Task**: 修复远程 issue #6/#7/#8（网关夹具路由/机台类型过滤/删除机台事务性）
**Branch**: `master`

### Summary

核实 lennylin-lab/product 全部 11 个 issue 与代码现状后，修复 3 个 P1：#6 网关补 master-data-fixture 正式路由并同步 README 路由表；#7 MachineMapper 两个查询补 resource_type='MACHINE' 过滤（共享 SQL 片段不动，getInfo 对非机台 id 由错数据变 data:null 已在 PRD 记为接受项）；#8 两个删除路径先校验 machine 存在性（缺失抛 ServiceException 不触碰 resource 表），单数方法补 @Transactional 与 versionService.bump 对齐批量版——关键发现是控制器单 id 请求实际走批量方法，且批量版事务因无异常不会回滚，只加事务无效。新增 MachineServiceImplDeleteTest 5 条离线单测；trellis-check 全项 PASS，gateway+master-data mvn test 全绿（25+10 用例）。未动单体 product-master（同款 mapper 缺陷待 follow-up）；issue 关闭留给用户确认。踩坑记录：Trellis ZCode hooks 用相对路径解析脚本，Bash 会话里 cd 会让后续所有 Bash 调用被 hook 失败阻断，本会话内已改用绝对路径规避。

### Git Commits

| Hash | Message |
|------|---------|
| `b7587ec` | fix(gateway): add missing /master/resource/fixture route |
| `32a41e4` | fix(master-data): filter machine list/detail by resource_type=MACHINE |
| `e0940bb` | fix(master-data): validate machine existence before deletion |

### Status

[OK] **Completed**


## Session 7: 修复远程 issue #9/#10/#11（密码脱敏/路径数字约束/分页缺省分支），远程 issue 全清零
<!-- trellis-session: v=2 fp=0efd7fb67c028efc -->

**Date**: 2026-09-18
**Task**: 修复远程 issue #9/#10/#11（密码脱敏/路径数字约束/分页缺省分支），远程 issue 全清零
**Branch**: `master`

### Summary

修复最后三个 OPEN issue：#9 SysUser.password 加 @JsonProperty(WRITE_ONLY)（核实 Redis 无 SysUser 缓存、写端点为死代码、登录用 DB 现查用户，零波及），JacksonContractTest 增序列化/反序列化双向用例；#10 全部 41 处单 id 路径变量加 \d+ 约束（17 控制器 5 服务，含质检发现的 TaskEventController 5 个 POST 动作子路径），复数 {xxxIds}/{dictCodes} 14 处（7 String[] + 7 Long[]）有意不动并在 issue 评论说明残留机制；#11 Customer/Product list 删除无参全量分支（TableSupport 缺省 1/10），无参 selectCustomerPage 四处删除并清 import。README 决议表补 #10/#9/#11 行并补录 #7/#8。trellis-check 全项 PASS（抓出清单遗漏与复数计数笔误，已修正）；五模块 mvn test 两轮全绿。三 commit 已推送，#9/#10/#11 已评论关闭——远程仓库 11 个 issue 全部关闭。踩坑重犯：Bash 会话里又带了一次 cd（写 jsonl 命令开头），hook 相对路径问题再次卡死会话，靠占位脚本恢复；hook 绝对路径修复（041a806）下个会话生效，本会话仍需守 '禁止 cd' 纪律。

### Git Commits

| Hash | Message |
|------|---------|
| `c0006b4` | fix(identity): drop password from user serialization |
| `644851f` | fix(api): require numeric single-id path variables across controllers |
| `20e68f0` | fix(demand,master-data): list endpoints always apply filters with default paging |

### Status

[OK] **Completed**
