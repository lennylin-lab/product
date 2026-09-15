# 09-15-fixture-compatibility — Implementation Plan

> 前置：09-15-fixture-modeling 已完成并提交（`aa8e211`）。阅读顺序：implement.jsonl →
> 本任务 design.md → 父任务 prd/design（KD1 与 Scheduling Semantics 节）→
> `microservices-platform.md` → database-guidelines / quality-guidelines。

## Checklist（按序执行）

- [x] 1. schema：`master_data_schema.sql` 新增 `fixture_mold_compatibility` 表（13 → 14 表，
  见 design.md 签名）；本地 init 重放验证幂等（计数器不清零语义保持）。
- [x] 2. master-data 实体与持久化：`FixtureMoldCompatibility` 实体 + mapper；兼容行维护
  挂到 fixture 资源聚合写路径（对照 machine 兼容的现有挂载方式），同事务 bump 版本计数。
- [x] 3. 契约：`FixtureMoldCompatibilityDTO` + `ResourceDTO.FixtureDTO.moldCompatibilities`；
  `InternalMasterDataController` fixture 批量加载分支一并带出兼容行（仍一次 IN 查询，禁止 N+1）。
- [x] 4. planning：`FixtureMoldCompatibility` 内存模型 + `Fixture.moldCompatibilityList` +
  loader 映射。
- [x] 5. 路由规则：`RouteEligibleResourceRule.requiresFixture()` default false；
  `RouteOperationConstants` 增加两个新规则码；`SetupMachineFixtureRule` /
  `InjectMachineFixtureRule` 产出 PERSON/MACHINE/FIXTURE 需求（FIXTURE 行 mandatory=1、
  resourceId=null、经 RouteRequirementFactory 创建）。
- [x] 6. 测试（design.md Tests Required 全项）：映射/孤儿防御/loader/新规则断言/
  旧三规则逐字段回归/registry 解析。
- [x] 7. 文档：`docs/TODO.md` 兼容小节状态更新；baselines §2.4 增量补一行（14 表）。

## Validation Commands

- `cd product-services && mvn -B -ntp clean test`（基准 202 全绿 + 新增用例，记录精确数字）
- 根仓库 `mvn -B -ntp -DskipTests compile`（单体回滚点 28/28）
- schema 幂等：init 脚本重放 2 次（一次性容器，勿动运行中栈）

## Review Gates

- 旧三规则（SETUP_MACHINE/INJECT_MACHINE/POST_WORKSTATION）的 buildRequirements 输出
  必须逐字段不变——有变化即 FAIL。
- 既有工序（eligibleResourceRule 未配置为新码）不得出现 FIXTURE 需求行。
- 单体/根 pom/根 schema.sql/compose.dev.yml 零改动；DTO 无持久化注解。
- 新代码构造器注入；测试离线可跑（新增 mapper 记得 MasterDataApplicationTest 的
  @MockitoBean 占位约定）。
- 全部勾选后交 trellis-check 校验，再进入 09-15-fixture-assignment-occupancy。

## Rollback Points

- 兼容数据层（表/实体/DTO/loader 映射）与规则层（新规则码）可独立回滚；
  移除新规则码配置即回到无夹具需求状态，计算器行为与 child-1 基线一致。

## 执行记录（2026-09-15，trellis-implement）

### 落地内容

- schema：`master_data_schema.sql` 在 `machine_mold_compatibility` 之后新增
  `fixture_mold_compatibility`（复合主键 fixture_id+mold_id、`is_compatible INT(1) DEFAULT 1`、
  DROP/CREATE 复位语义逐字镜像机模兼容表），脚本头注释补记"额外表"权属说明；13 → 14 表，
  根 schema.sql 零改动。
- master-data 持久化：`FixtureMoldCompatibility` 实体（逐字镜像 `MachineMoldCompatibility`）
  + `FixtureMoldCompatibilityMapper`。写路径为**记录在案的约定偏差**：核对 `MachineServiceImpl`
  全文后确认机模兼容行在 master-data **没有任何写路径**（仅 `InternalMasterDataController`
  只读批量带出，历史上直接落库维护），故按派发指示落为 `IFixtureService` 上的最小维护入口
  `saveMoldCompatibilities(fixtureId, rows)`（先删后插全量替换；沿用 fixture 聚合写路径既有
  约定：`@Transactional(rollbackFor)` + 成功后同事务 `MasterDataVersionService.bump()`，
  mold_id 为空的脏入参行过滤不落库）。
- 契约（只加不改）：`FixtureMoldCompatibilityDTO { fixtureId, moldId, isCompatible }`（顶层
  DTO，镜像 `MachineMoldCompatibilityDTO`，无任何持久化注解）；`ResourceDTO.FixtureDTO` 增加
  `List<FixtureMoldCompatibilityDTO> moldCompatibilities`（挂载方式与信封级机台兼容行一致：
  无兼容数据保持 null）；`MasterDataBatchQueryApi.getResources` javadoc 补记增量。
  `InternalMasterDataController.getResources` 在既有 fixture IN 查询块旁新增**同一次批量查询**
  的兼容行 map（仍一次 IN、无 N+1），仅经 `fixture != null && resourceType == FIXTURE` 既有
  防御块挂载（fixture_id 指向非 FIXTURE 资源的孤儿兼容行不泄漏）。
- planning：`domain/model/FixtureMoldCompatibility`（镜像 `MachineMoldCompatibility`，javadoc
  写明 KD1 显式允许清单语义）；`Fixture.moldCompatibilityList`；
  `SchedulingSnapshotLoader.toResource` 在 fixture 分支同步映射兼容行（缺失时映射为空表），
  新增 `toFixtureCompatibility` 映射器；AVAILABLE 过滤/版本漂移守卫零改动。
- 路由规则：`RouteEligibleResourceRule` 增加 `default boolean requiresFixture() { return false; }`
  （既有三规则零改动、零覆写）；`RouteOperationConstants` 增加
  `RULE_SETUP_MACHINE_FIXTURE` / `RULE_INJECT_MACHINE_FIXTURE`；`SetupMachineFixtureRule`
  （PERSON"调机员" + MACHINE + FIXTURE）与 `InjectMachineFixtureRule`（MACHINE + FIXTURE）
  均为 `@Component`（经 `RouteRuleRegistry` 构造注入自动注册）、triggersChangeover=true、
  requiresMachine=true、requiresFixture=true；需求行全部经 `RouteRequirementFactory.createRequirement`
  创建（FIXTURE 行 isMandatory=1、resourceId=null、resourceRole=null 与工厂对非人员角色的
  既有约定一致；夹具选择是 child-3 计算器职责）。
- 文档：`docs/TODO.md` 夹具 P1 行更新为"兼容规则已接入，分配与占用计算待补齐"；
  归档 baselines.md §2.4 追加兼容增量记录（13 表 → 14 表，冻结内容不变）。

### 验证证据（数字全部来自本轮 clean 运行，日志留存 /tmp/fixture-compat-clean-test.log）

- `cd product-services && mvn -B -ntp clean test`（全 reactor 13 模块，temurin-17.0.20+8）：
  **BUILD SUCCESS，215 tests, 0 failures, 0 errors, 0 skipped**（child-1 基线 202 + 新增 13）。
  分模块：cloud-common 21 / cloud-security 11 / cloud-messaging 8 / gateway 17 / identity 31 /
  master-data 11（+3：InternalMasterDataControllerFixtureCompatibilityTest 3；
  既有 ApplicationTest 3、DirectPort 1、ResourceMappingTest 2、ProductRouteServiceValidationTest 2 原样绿）/
  demand-service 27 / planning 72（+10：FixtureAwareRouteRuleTest 4、
  LegacyRouteRuleRegressionTest 4、SchedulingSnapshotLoaderTest 9→11）/
  execution 17。父 pom 与三个 api 模块无测试。
- 根仓库 `mvn -B -ntp -DskipTests compile`（单体回滚点）：**28/28 模块 BUILD SUCCESS**。
- schema 幂等重放（一次性 docker mysql:8.4 容器，宿主端口 33078，未触碰运行中栈的
  product-mysql 33066 与 8080/8101-8105 服务端口，验证后容器已删除）：init 脚本连跑 3 次
  全部 exit 0 无错误；表数稳定 14（11 单体主数据表 + master_data_data_version + fixture +
  fixture_mold_compatibility）；新表 3 列、复合主键 (fixture_id, mold_id)、is_compatible
  DEFAULT 1；预置 `master_data_data_version=41` 后重跑脚本，计数器仍为 41（不清零语义保持）。

### Review Gates 逐项核验

- 旧三规则 buildRequirements 逐字段不变：`SetupMachineRule` / `InjectMachineRule` /
  `PostWorkstationRule` 三个主代码文件 git diff 零改动；`LegacyRouteRuleRegressionTest`
  对每条需求行的全部 10 个字段 + 规则语义标志逐项断言（含 requiresFixture() 默认 false），
  全绿。
- 既有工序无 FIXTURE 需求行：`FixtureAwareRouteRuleTest.builderShouldEmitFixtureRequirement-
  OnlyForConfiguredFixtureAwareRule` 断言旧规则码与 opCode 兜底两条路径均无 FIXTURE 行；
  既有 `OperationResourceRequirementBuilderTest` 原样绿。
- 单体/根 pom.xml/根 schema.sql/compose.dev.yml：git status 确认零改动；DTO 无持久化注解；
  新代码无 System.out、构造器注入；错误契约未触碰。
- `master_data_schema.sql` 已在 `*.sql` 白名单内保持 tracked（git check-ignore 确认）。

### 偏差与说明

- **机模兼容无写路径 → 最小维护入口**（上文已述）：`saveMoldCompatibilities` 为新增的最小
  add/update 入口，非"完全对照 machine 兼容挂载方式"（后者不存在写路径可对照）；
  读路径（契约带出）与机模兼容挂载方式完全一致。
- 既有 `InternalMasterDataControllerResourceMappingTest` 两个用例各补一行
  `FixtureMoldCompatibility` 空打桩（controller 新增第 6 批量查询后 mock 链必需），
  断言零变化；新增 `FixtureMoldCompatibilityMapper` 按约定在 `MasterDataApplicationTest`
  补 `@MockitoBean` 占位。均属既有测试约定的机械延伸，非行为变更。
- design.md Contracts 写"无兼容数据 → 空列表或 null（以实际代码为准）"：落地取 null
  （与信封级机台兼容行"非空才 set"的现状一致）；planning 侧映射为空表，消费端无歧义。

## 移交 09-15-fixture-assignment-occupancy（check 非阻塞项）

- `FixtureServiceImpl.saveMoldCompatibilities` 当前无调用方（维护入口）且事务/回滚不 bump
  语义无用例覆盖 —— child-3 接线夹具选择验证时一并补测试或暴露维护端点。
- schema 幂等 ×3 的容器重放未留存 transcript（离线约束）；结构性依据已由 check 从脚本本身
  核实（业务表 DROP/CREATE、计数器 IF NOT EXISTS 且脚本从不 INSERT 计数）。
