# 09-15-fixture-modeling — Design

> 架构与决策的权威来源是父任务 `09-15-fixture-scheduling/design.md`；本文只写本子任务的落地形态。

## Scope

只做「夹具作为一等协同资源的建模与快照加载」：FIXTURE 资源类型常量、master_data_db 扩展表、
resources/batch 契约扩展、planning 内存模型与 SchedulingSnapshotLoader 映射、测试。
兼容规则（fixture↔mold）、路由需求生成、计算器分配/占用分别属于
`09-15-fixture-compatibility` 与 `09-15-fixture-assignment-occupancy`，本任务不做。

## Signatures（目标形态，全部为增量）

### master_data_db schema（`product-master-data/src/main/resources/db/init/master_data_schema.sql`）
- `resource.resource_type` 列注释追加 FIXTURE（夹具）——仅注释，不动列定义与既有类型。
- 新增扩展表，沿用 machine/mold「扩展表主键 = resource.resource_id」的既有约定：
  ```sql
  CREATE TABLE fixture (
      fixture_id   BIGINT(20)  NOT NULL COMMENT '夹具ID(与resource.resource_id对应)',
      fixture_code VARCHAR(64) DEFAULT '' COMMENT '夹具业务编号',
      PRIMARY KEY (fixture_id),
      KEY idx_fixture_code (fixture_code)
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='夹具表';
  ```
  兼容性数据（fixture_mold_compatibility）不在本任务，避免与 child-2 的 DDL 冲突。

### master-data Java
- `ResourceConstants`（master-data 侧如有独立常量类则同步；planning 侧
  `common/constant/ResourceConstants.java` 增加 `RESOURCE_TYPE_FIXTURE = "FIXTURE"`）。
- `domain/entity/Fixture.java`（@TableName("fixture")，模式同 `Mold.java`）、
  `mapper/FixtureMapper.java`、最小 service 纳入既有资源聚合写路径；
  fixture 新增/修改走既有资源写事务并调用 `MasterDataVersionService.bump()`
  （快照漂移检测依赖版本计数，见 Phase 4 既有约定）。
- 资源创建/更新入口按 machine/mold 扩展的现有挂载方式携带 fixture 扩展（以实际代码为准，
  不发明新端点风格）。

### product-master-data-api（契约，只加不改）
- `ResourceDTO` 增加嵌套 `FixtureDTO { fixtureId, fixtureCode }`（模式同 MachineDTO/MoldDTO）。
- DTO 不得带任何持久化注解；旧消费者不读取新字段，序列化向后兼容。

### planning
- `domain/model/Fixture.java`（纯内存模型：fixtureId、fixtureCode，模式同 `Machine.java`）；
  `domain/model/Resource.java` 增加 `fixture` 字段（可空）。
- `SchedulingSnapshotLoader.toResource(...)`：`dto.getFixture() != null` 时映射为 `Fixture`；
  AVAILABLE 过滤、snapshotVersion、漂移守卫全部沿用现状，不加新逻辑。

## Contracts

- `resources/batch` 响应新增可选 `fixture` 对象；请求形状不变（仍按 resourceIds/types 批量）。
- master-data 不可达/响应非法时 planning 行为不变（既有 fail-closed 语义，不新增分支）。

## Validation & Error Matrix

| 条件 | 行为 |
|------|------|
| resource.status != AVAILABLE 的 fixture | 不进入排程快照（沿用既有资源过滤，不特判） |
| dto.fixture 为 null（旧 master-data 或非夹具资源） | planning Resource.fixture 为 null，非夹具路径零变化 |
| fixture 写入事务失败 | 版本计数不递增（同事务 bump） |

## Good/Base/Bad Cases

- Good：FIXTURE 资源 + fixture 扩展行 → resources/batch 返回 fixture 聚合 → loader 装载。
- Base：MACHINE/MOLD/PERSON/WORKSTATION 资源 → 响应与快照与现状逐字节一致（无 fixture 字段值）。
- Bad：为 fixture 单开直连 master_data_db 的查询、或在 planning 新增 fixture 专用加载调用——禁止。

## Tests Required

1. master-data：`InternalMasterDataController`（或资源聚合入口）DTO 映射测试——有/无 fixture 扩展、
   非 FIXTURE 资源不带 fixture 字段（离线 MockMvc/Mockito，沿用既有测试风格）。
2. planning：`SchedulingSnapshotLoaderTest` 增加 fixture 资源映射用例（可用/不可用/缺失扩展），
   既有非夹具快照用例保持绿。
3. schema：`master_data_schema.sql` 重复执行幂等（fixture 表 + 计数器不清零语义不受影响）；
   表数与 baselines/ADR-0005 的增量记录同步更新（12 表 → 13 表，文档随代码改）。

## Wrong vs Correct

#### Wrong
- 在 planning 侧直接 `SELECT * FROM master_data_db.fixture`（跨库，ADR-0005 禁止）。
- 为 fixture 新增独立的快照拉取调用（应并入既有 resources/batch 批量契约）。
- 在旧单体模块加 fixture 代码（R1：单体只读对照）。

#### Correct
- fixture 数据全部经既有 `resources/batch` 契约以可选嵌套对象下发；
  planning 仅在内存模型上做增量映射。
