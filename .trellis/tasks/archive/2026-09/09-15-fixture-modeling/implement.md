# 09-15-fixture-modeling — Implementation Plan

> 前置阅读顺序：`implement.jsonl` 清单 → 父任务 prd/design/implement → 本任务 design.md →
> `.trellis/spec/backend/microservices-platform.md`（跨域读取/契约/版本计数规则）→
> database-guidelines / directory-structure / quality-guidelines。

## Checklist（按序执行）

- [x] 1. 常量与注释：planning `ResourceConstants` 增加 `RESOURCE_TYPE_FIXTURE`；master-data 侧
  同类常量/字典/转换器文本按实际代码补齐 FIXTURE（含 `master_data_schema.sql` 的
  `resource.resource_type` 列注释）。
- [x] 2. schema：`master_data_schema.sql` 新增 `fixture` 扩展表（见 design.md 签名；只加不改）。
  注意执行本地 init 重放验证幂等（fixture 表 + `master_data_data_version` 不清零）。
- [x] 3. master-data 实体与持久化：`Fixture` 实体 + `FixtureMapper`（模式对照 `Mold`/`MoldMapper`），
  资源聚合写路径挂载 fixture 扩展，写事务内 `MasterDataVersionService.bump()`。
- [x] 4. 契约：`ResourceDTO` 增加嵌套 `FixtureDTO`；`InternalMasterDataController.getResources(...)`
  批量加载 fixture 扩展并映射（一次批量查询，禁止 N+1）。
- [x] 5. planning：`domain/model/Fixture.java` + `Resource.fixture` 字段 +
  `SchedulingSnapshotLoader.toResource(...)` 映射。
- [x] 6. 测试：master-data DTO 映射用例（有/无扩展、非 FIXTURE 不带字段）、
  planning loader 用例（可用/不可用/缺扩展）、既有非夹具用例保持绿。
- [x] 7. 文档：`docs/TODO.md` 夹具建模小节勾选/状态更新（如该文件有对应条目）；
  baselines/ADR 增量表数以代码实际为准同步。

## Validation Commands

- `cd product-services && mvn -B -ntp clean test`（全 reactor，基准 197 用例全绿 + 新增用例）
- 根仓库 `mvn -B -ntp -DskipTests compile`（单体回滚点，28/28 SUCCESS）
- schema 幂等：`identity_db` 之外的 `master_data_db` init 脚本连跑 2 次行数稳定

## Review Gates

- 不修改旧单体模块 / 根 pom.xml / 根 schema.sql / compose.dev.yml。
- DTO 无持久化注解；planning 不新增对 master_data_db 的任何直接访问。
- 版本计数：fixture 写路径必须在同事务 bump（否则 child-3 的漂移守卫失效）。
- 全部勾选后交 trellis-check 校验，再进入 09-15-fixture-compatibility。

## Rollback Points

- 本任务全部为增量（常量/注释/新表/新 DTO 字段/新映射分支）；
  回滚 = 还原本任务提交即可，无数据迁移反向脚本。
- 若 schema 已在本地库执行：`master_data_db` 重跑 init 脚本即回到种子态。

## 执行记录（2026-09-15，trellis-implement）

### 落地内容

- 常量/注释：planning 与 master-data 两个 `ResourceConstants` 均加 `RESOURCE_TYPE_FIXTURE = "FIXTURE"`；
  master-data `Resource` 实体 `@Excel` readConverterExp 与 javadoc 追加 `FIXTURE=夹具`；
  `master_data_schema.sql` 的 `resource.resource_type` 列注释追加 FIXTURE夹具（仅注释，列定义不动）。
- schema：`fixture` 扩展表按 design.md 签名落在 `mold` 之后（DROP/CREATE 重置语义同 machine/mold；
  扩展表主键 = resource.resource_id，`idx_fixture_code` 索引）；脚本头注释补记该"额外表"权属说明。
- master-data 持久化：`Fixture` 实体（模式同 `Mold`）、`FixtureMapper`、`FixtureResource` 聚合入参
  （模式同 `MachineResource`）、`IFixtureService`/`FixtureServiceImpl`、`FixtureController`
  （`/master/resource/fixture`，list/getInfo/add/edit/remove）。写路径完全沿用 machine 既有约定：
  resource 主行 + fixture 扩展行同事务写入，成功后同事务 `versionService.bump()`（回滚则计数不递增）。
  master-data `Resource` 聚合实体增加 `@TableField(exist = false) fixture` 挂载字段（同 mold/machine）。
  新类按平台规范用构造器注入（`@RequiredArgsConstructor`/显式构造器）；machine 既有文件不动其
  `@Autowired` 风格（单体逐字移植契约优先）。
- 契约（只加不改）：`ResourceDTO` 增加嵌套 `FixtureDTO { fixtureId, fixtureCode }`（无任何持久化注解）；
  `MasterDataBatchQueryApi.getResources` javadoc 补记增量；`InternalMasterDataController.getResources`
  与 machine/mold 同款**一次批量查询**加载 fixture 行（无 N+1），仅对 FIXTURE 类型资源挂载
  （孤儿扩展行脏数据不会泄漏到非夹具资源）。请求形状不变，旧消费者不读新字段，向后兼容。
- planning：`domain/model/Fixture.java` 新建；`Resource.fixture` 可空字段；
  `SchedulingSnapshotLoader.toResource(...)` 在 `dto.getFixture() != null` 时映射。
  AVAILABLE 过滤、snapshotVersion、漂移守卫零改动；非夹具路径零变化。
- 文档：`docs/TODO.md` 夹具 P1 行更新为"建模已完成，兼容/分配占用待做"；
  归档 baselines.md 增加 §2.4 补充记录（12 表 → 13 表）；ADR-0005 增加增量记录注
  （沿用其 §2.3"冻结内容不变 + 日期补充"的既有修订模式）。

### 验证证据（数字全部来自本轮 clean 运行）

- `cd product-services && mvn -B -ntp clean test`（全 reactor 13 模块，JDK temurin-17.0.20+8）：
  **BUILD SUCCESS，202 tests, 0 failures, 0 errors, 0 skipped**（基线 197 + 新增 5）。
  分模块：cloud-common 21 / cloud-security 11 / cloud-messaging 8 / gateway 17 / identity 31 /
  master-data 8（含新增 InternalMasterDataControllerResourceMappingTest 2 个；
  MasterDataApplicationTest 3、DirectPortSecurityTest 1、ProductRouteServiceValidationTest 2 原样绿）/
  demand-service 27 / planning 62（SchedulingSnapshotLoaderTest 6→9，新增 3 个夹具用例）/
  execution 17。父 pom 与三个 api 模块无测试。
- 根仓库 `mvn -B -ntp -DskipTests compile`（单体回滚点）：**28/28 模块 BUILD SUCCESS**。
- schema 幂等重放（一次性 docker mysql:8.4 容器、端口 33077，未触碰运行中栈的 33066 实例，
  验证后容器已删除）：init 脚本连跑 2 次 —— 第 2 次 mysql exit 0；表数稳定 13
  （11 单体主数据表 + master_data_data_version + fixture）；`fixture` 表 2 列完整；
  预置 `master_data_data_version=41` 后重跑脚本，计数器仍为 41（不清零语义保持）；
  `resource.resource_type` 列注释含 FIXTURE夹具。

### 偏差与说明

- design.md 测试项 1 写"离线 MockMvc/Mockito"：实际用纯 Mockito 单测
  （`mockStatic(Db.class)` + 直接方法调用），未走 MockMvc——沿用仓库 quality-guidelines 的
  纯单测风格，且 controller 构造器注入可直接实例化；结论等价（DTO 映射断言覆盖有/无扩展、
  非 FIXTURE 不带字段、孤儿扩展行防御）。
- 新增 `FixtureMapper` 需同步在 `MasterDataApplicationTest` 补一行 `@MockitoBean`（该离线冒烟
  测试按既有约定对每个 mapper 显式 mock 占位，MyBatis-Plus 自动装配在离线测试中被排除）；
  属既有测试约定的机械延伸，非行为变更。
- `RETURNS_SELF` 对 MyBatis-Plus 链式包装器的泛型桥接方法（返回类型擦除为 Object）不生效，
  测试中以自定义 Answer 返回 mock 自身解决（测试内实现细节，不影响生产代码）。
- `.gitignore` 含 `*.sql`（仓库既有约定）：实施时 `master_data_schema.sql` 改动一度只存在于
  工作区；主会话核查发现该规则已连累全部 5 个服务 init 脚本与 dev-up 聚合脚本从未入库，
  已在提交 `b6ab3b6` 增加白名单并补 tracked（含本任务 fixture 表 DDL）；根 schema.sql 零改动。
