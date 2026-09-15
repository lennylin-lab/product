# 09-15-fixture-compatibility — Design

> 架构权威来源：父任务 `09-15-fixture-scheduling/design.md`（KD1：MVP 兼容粒度 = fixture ↔ mold，
> 显式允许清单，无兼容数据即不可排程）。本文只写本子任务落地形态。

## Scope

兼容数据的存储与下发（master_data_db + 契约 + planning 内存模型）、
夹具感知路由规则与 FIXTURE 资源需求生成（仅显式配置的规则码）。
计算器选择/占用/持久化属于 `09-15-fixture-assignment-occupancy`，本任务不做。

## Signatures（全部增量）

### master_data_db schema（`master_data_schema.sql`，13 → 14 表）
```sql
CREATE TABLE fixture_mold_compatibility (
    fixture_id    BIGINT(20)  NOT NULL COMMENT '夹具ID',
    mold_id       BIGINT(20)  NOT NULL COMMENT '模具ID',
    is_compatible INT(1)   DEFAULT 1 COMMENT '是否兼容(1兼容 0不兼容)',
    PRIMARY KEY (fixture_id, mold_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='夹具模具兼容表';
```
镜像 `machine_mold_compatibility`（第 81–86 行）的结构与复位语义；
维护路径沿用 machine 兼容的现有挂载方式（经资源聚合写路径 + 同事务版本 bump）。

### master-data Java
- `domain/entity/FixtureMoldCompatibility.java`（镜像 `MachineMoldCompatibility`）+ `FixtureMoldCompatibilityMapper`。
- 兼容行写入走 fixture 资源聚合写事务并 bump 版本计数（drift 检测依赖）。

### product-master-data-api（只加不改）
- `FixtureMoldCompatibilityDTO { fixtureId, moldId, isCompatible }`（镜像 MachineMoldCompatibilityDTO）。
- `ResourceDTO.FixtureDTO` 增加 `List<FixtureMoldCompatibilityDTO> moldCompatibilities`
  （镜像 MachineDTO.moldCompatibilities 的既有下发方式）；旧消费者不受影响。

### planning
- `domain/model/FixtureMoldCompatibility.java`（镜像 `MachineMoldCompatibility`）；
  `domain/model/Fixture.java` 增加 `moldCompatibilityList`；
  `SchedulingSnapshotLoader` 的 fixture 映射分支同步装载兼容行（仍一次批量查询）。
- `ResourceConstants` 无需新类型（FIXTURE 已在 child-1 落地）。

### 路由规则（仅显式规则码产出 FIXTURE 需求）
- 新增两个具体规则（Spring 组件，经 `RouteRuleRegistry` 构造注入自动注册）：
  - `SetupMachineFixtureRule`（code=`RULE_SETUP_MACHINE_FIXTURE`）：PERSON + MACHINE + FIXTURE，
    `triggersChangeover()=true`、`requiresMachine()=true`。
  - `InjectMachineFixtureRule`（code=`RULE_INJECT_MACHINE_FIXTURE`）：MACHINE + FIXTURE，同上语义。
  - 产出经 `RouteRequirementFactory.createRequirement(...)`，FIXTURE 行 `isMandatory=1`、
    resourceId 为 null（选择发生在 child-3 计算器）。
- `RouteEligibleResourceRule` 接口增加 default 方法 `default boolean requiresFixture() { return false; }`
  （增量，既有三规则不动），两个新规则返回 true——child-3 计算器可据此判断而无需扫描需求行。
- `RouteOperationConstants` 增加 `RULE_SETUP_MACHINE_FIXTURE` / `RULE_INJECT_MACHINE_FIXTURE`。
- 工序启用方式不变：`route_operation.eligible_resource_rule` 配置为新规则码即产出 FIXTURE 需求；
  不配置则走既有 opCode 兜底，SETUP/INJECT/POST_QC_PUTAWAY 行为零变化。

## Contracts

- `resources/batch` 响应的 `fixture.moldCompatibilities` 为可选列表；无兼容数据 → 空列表或 null
  （与 machine 兼容行的现状下发方式保持一致，以实际代码为准）。
- 兼容语义（供 child-3 消费，本任务只备数据）：`isCompatible=1` 才允许；所选模具无任何兼容行
  或兼容行为 0 → 该任务对夹具不可满足（显式清单语义，不静默放行）。

## Validation & Error Matrix

| 条件 | 行为 |
|------|------|
| 工序 eligibleResourceRule = 新规则码 | 需求含 PERSON/MACHINE（视规则）+ FIXTURE 强制行 |
| 工序沿用旧规则码 / 空 | 需求与现状完全一致（无 FIXTURE 行） |
| fixture 无任何兼容行 / 所选 mold 兼容=0 | 数据照常下发；不可满足的裁决在 child-3 计算器 |
| 兼容行写入事务失败 | 版本计数不递增（同事务 bump） |

## Tests Required

1. master-data：DTO 映射（兼容行挂到 FixtureDTO；无兼容行为 null/空）+ 孤儿防御
   （兼容行指向非 FIXTURE 资源不泄露）——沿用 child-1 的映射测试风格。
2. planning loader：fixture 兼容行映射进 Fixture.moldCompatibilityList；无兼容行资源不炸。
3. 规则：两个新规则的 buildRequirements 断言（类型、mandatory、code、requiresFixture）；
   旧三规则回归测试保持绿（buildRequirements 输出逐字段不变）。
4. registry：新规则码可被 findRule/requireRule 解析。

## Wrong vs Correct

#### Wrong
- 在 fixture 无兼容数据时默认「全兼容」放行（父 KD1：显式清单，缺失即不可满足）。
- 用 resource_capability 表达 fixture↔mold 物理适配（语义混用，父 design 明确禁止）。
- 新规则在 buildRequirements 里给 FIXTURE 行填 resourceId（选择是 child-3 的职责）。

#### Correct
- 兼容数据经既有批量契约随 fixture 聚合下发；FIXTURE 需求只由显式新规则码产生；
  旧行为完全不变。
