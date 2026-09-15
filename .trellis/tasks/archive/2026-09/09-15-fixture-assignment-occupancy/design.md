# 09-15-fixture-assignment-occupancy — Design

> 架构权威来源：父任务 `09-15-fixture-scheduling/design.md`（Data Flow 第 5–6 步、
> Scheduling Semantics）。前序已就绪：FIXTURE 需求由新规则码产生（mandatory、resourceId=null）、
> `Fixture.moldCompatibilityList` 显式允许清单、`requiresFixture()` 信号、
> `resolveRequiredResourceTypes` 已泛化加载 FIXTURE 资源（child-2 check 确认）。

## Scope

计算器的夹具选择与时间占用（机台分支）、运行时上下文、需求解析回填、持久化验证。
兼容数据裁决语义（显式清单）在本任务落地为可排程性判断；工位分支 + 夹具为 MVP 外延（见 Out of Scope）。

## Signatures（全部为对现有显式结构的审慎扩展）

### TaskSchedulingCalculator（product-planning service/impl）

- `ResourceChoice`（line ~1355 内部静态类）增加 `fixtureId`、`fixtureSequence`（可空，
  与 moldId/moldSequence 同模式）。
- `chooseResources(...)` 机台分支：模具选定后增加夹具选择阶段——
  1. 从 `task.getResourceRequirementList()` 过滤 mandatory 的 FIXTURE 需求行；无该行 → 跳过
     （零夹具路径行为不变）。
  2. 候选 = `schedulingContext.getResourcesByType().get(FIXTURE)` 中 status 可用
     （沿用既有资源过滤口径）且 `fixture != null` 的资源。
  3. 兼容过滤（父 KD1 显式清单）：`fixture.moldCompatibilityList` 中须存在
     `moldId == 所选模具`（模具需求缺失时按任务 MOLD 需求行，无 MOLD 需求的夹具任务
     按现实语义无法判定适配 → 视为不可满足）且 `isCompatible == 1` 的行；
     候选过滤后为空 → 任务不可排程，失败语义与既有「机台/模具/人员不可用」一致
     （照抄现有路径的失败风格，不发明新的异常类型）。
  4. 在候选中选最早可用（`runtimeContext.getNextAvailableTime(FIXTURE, fixtureId)`，
     无记录视为初始可用，与机台/模具口径一致），序列 `getNextSequence(FIXTURE, fixtureId)`。
  5. 有效开始时间纳入夹具：`effectiveStart = max(机台, 模具, 人员, 工位, 夹具, earliestStart,
     assignmentStart)` 后按既有班次/日历规则重算 plannedEnd（沿用现有重算调用）。
- 主循环（line ~137–172）：`choice.fixtureId != null` 时
  `resourceSequenceMap.put(FIXTURE, choice.fixtureSequence)` 与
  `runtimeContext.update(FIXTURE, fixtureId, plannedEnd, fixtureSequence)`（既有逐类型块的模式）。
- `resolveSelectedResources(...)` 增加 `fixtureId` 参数与 FIXTURE 分支（既有 if-else 链延伸），
  FIXTURE 需求副本回填所选夹具 ID。
- 工位分支（`chooseWorkstationResources`）本任务不接夹具：两个 MVP 规则均为机台规则，
  工位任务不产生 FIXTURE 需求；`requiresFixture()` 为 true 但走工位分支的组合在 MVP 中不存在，
  如遇到按不可排程处理并记录。

### 持久化（TaskAssignmentPersistenceService）

- 预期「零改动」：`appendRequirementAssignments(...)` 泛化路径按解析后需求行写
  task_assignment_resource（Phase 4 已验证按 resourceType 泛化）。
- 本任务以测试证明 FIXTURE 行落库正确（resource_type/requirement_id/planned_start/planned_end/
  sequence_on_resource），仅当泛化路径确有缺口时做最小修补并说明。

### 失败与占用语义

- 夹具占用窗口 = 任务 plannedStart..plannedEnd（与模具/人员/工位行一致）。
- 夹具序列独立于其他类型（`ResourceRuntimeContext` 按 (FIXTURE, fixtureId) 维护）。
- 排程内多任务竞争同一夹具：贪心序内先到先得，后任务从其 nextAvailableTime 起算
  （与机台/模具行为同模式）。

## Validation & Error Matrix

| 条件 | 行为 |
|------|------|
| 任务无 FIXTURE 需求行 | 全路径与现状逐字节一致（回归基线） |
| 有 FIXTURE 需求、候选兼容夹具为空 | 任务不可排程，失败语义与既有资源不可用一致 |
| 兼容行缺失或 isCompatible=0 | 视为不兼容（显式清单，无默认放行） |
| 夹具可用时间晚于其他资源 | plannedStart 被推迟到夹具可用时间，占用窗口随之 |
| FIXTURE 需求 resourceId 已指定（预指定） | 尊重预指定（沿用既有 resolve 语义：仅回填 null） |

## Tests Required

1. 计算器（沿用 `TaskSchedulingCalculatorTest` 既有桩风格）：兼容夹具存在且即时可用 →
   选中 + 窗口不受影响；兼容夹具较晚可用 → plannedStart/plannedEnd 推迟；
   无兼容夹具 / 兼容行缺失 / isCompatible=0 → 任务不可排程；
   多任务竞争同一夹具 → 序列与可用时间推进正确。
2. `resolveSelectedResources`：FIXTURE 副本回填 fixtureId、预指定不被覆盖。
3. 持久化（沿用 `TaskAssignmentPersistenceServiceTest` 桩风格）：FIXTURE 行写入
   task_assignment_resource 的字段逐项断言。
4. 回归：既有计算器 14 用例 + 持久化 2 用例 + 路由规则用例全部保持绿。
5. `FixtureServiceImpl.saveMoldCompatibilities`（child-2 移交）：补事务写/bump 语义用例
   （保存失败或回滚不递增版本计数；成功递增）。

## Wrong vs Correct

#### Wrong
- 为夹具在 task_assignment 上加列（task_assignment 保持机台中心，占用行进
  task_assignment_resource——父 design 明确）。
- 无兼容数据默认全兼容；或兼容过滤放在 master-data 侧（过滤是 planning 的排程裁决）。
- 在工位分支顺手支持夹具（MVP 外延，未验证的分支扩展）。

#### Correct
- 夹具是机台分支内、模具之后的第三级协同选择：兼容过滤 → 最早可用 → 时间纳入 →
  运行时更新 → 需求回填 → 泛化持久化。
