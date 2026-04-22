# Analysis Discussion

**Session ID**: ANL-2026-04-21-docs-todo-md-完成接下来的优化点
**Topic**: docs/TODO.md 完成接下来的优化点
**Started**: 2026-04-21T00:00:00+08:00
**Dimensions**: implementation, performance, decision
**Depth**: standard

## Table of Contents
- [Analysis Context](#analysis-context)
- [Current Understanding](#current-understanding)
- [Discussion Timeline](#discussion-timeline)
- [Decision Trail](#decision-trail)
- [Synthesis & Conclusions](#synthesis--conclusions)

## Current Understanding

### What We Established
- `docs/TODO.md` 中尚未完成的项里，`product-pps` 的“修正待排任务排序与边界行为”最适合作为当前轮次直接落地的优化点。
- 当前 `TaskSchedulingQueryService#loadReadyTaskList` 仅按 `status=READY` 全量拉取，没有前置去除无效任务，也没有保证数据库侧稳定顺序。
- 当前全量排程在 `TaskSchedulingCoordinator` 内做策略排序，但单任务排程 `TaskAssignmentServiceImpl#scheduleTasks` 对传入列表没有统一排序与去重保护。

### What Was Clarified
- ~~下一步应继续扩展资源模型~~ → 资源模型扩展涉及 `product-domain`、`product-master`、`product-pps` 多模块和数据库模型，当前回合更适合先处理低风险、可闭环的排序与边界行为。

### Key Insights
- 这一轮最有价值的优化不是新增复杂策略，而是让 READY 任务输入集合“可预测、可去重、可解释”，这样后续无论扩展何种排程策略，基础行为都稳定。

## Analysis Context
- Focus areas: 待排任务稳定排序、重复派工过滤、空任务输入保护、无可用机台边界
- Perspectives: Technical
- Depth: standard

## Initial Questions
- 当前 READY 任务是否存在已产生 `task_assignment` 但状态尚未刷新完成的重复派工窗口？
- 查询层是否应先做稳定排序，避免默认 UUID 顺序干扰批次内工序顺序？
- 单任务/全量排程是否应复用同一套输入标准化逻辑？

## Initial Decisions
> **Decision**: 本轮优先完成 `product-pps` 的“待排任务排序与边界行为”。
> - **Context**: `TODO.md` 中该项为 P2，但与现有代码高度贴合，改动面集中，具备单轮交付条件。
> - **Options considered**: 继续扩展排程策略；先做资源模型扩展；先做排序与边界优化。
> - **Chosen**: 先做排序与边界优化。 — **Reason**: 风险低、收益直接、测试可补齐，并能为后续策略和资源模型演进提供稳定基础。
> - **Rejected**: 排程策略扩展需要新增业务规则；资源模型扩展依赖更多领域对象和数据库约束，超出当前一轮实现范围。
> - **Impact**: 当前实现范围锁定在 `TaskSchedulingQueryService`、`TaskAssignmentServiceImpl` 与对应测试。

---

## Discussion Timeline

### Round 1 - Explore (2026-04-21T00:00:00+08:00)

#### User Input
用户要求基于 `docs/TODO.md` 完成接下来的优化点。

#### Decision Log
> **Decision**: 将 `TODO.md` 中“接下来的优化点”具体化为一个本轮可执行子项。
> - **Context**: 文档里有多个未完成方向，直接平铺实现会造成范围失控。
> - **Options considered**: 按建议顺序推进 P1 资源模型；继续扩展排程策略；先修正排序与边界行为。
> - **Chosen**: 先修正排序与边界行为。 — **Reason**: 当前代码已有明确缺口，且已有 `TaskSchedulingCalculatorTest` 风格可沿用。
> - **Rejected**: 资源模型扩展需要领域建模；策略扩展需要更多业务定义。
> - **Impact**: 后续代码改动只覆盖可独立验证的排程输入标准化问题。

#### Key Findings
> **Finding**: `TaskSchedulingQueryService#loadReadyTaskList` 只做 `READY` 状态过滤，没有稳定排序，也没有过滤已存在派工记录的任务。
> - **Confidence**: High — **Why**: 直接代码证据见 `product-pps/src/main/java/com/product/pps/service/impl/TaskSchedulingQueryService.java`
> - **Hypothesis Impact**: Confirms hypothesis "READY 任务集合未标准化"
> - **Scope**: 全量排程输入

> **Finding**: `TaskSchedulingCalculator#taskComparator` 已经提供稳定比较链，但它发生在内存排序阶段，无法覆盖单任务列表、重复输入和查询侧污染。
> - **Confidence**: High — **Why**: 直接代码证据见 `product-pps/src/main/java/com/product/pps/service/impl/TaskSchedulingCalculator.java`
> - **Hypothesis Impact**: Modifies hypothesis "排序问题完全由 Calculator 处理"
> - **Scope**: 全量排程与单任务排程入口

> **Finding**: 单任务入口只校验 `status=READY`，没有再次确认该任务是否已有有效 `task_assignment`。
> - **Confidence**: Medium — **Why**: `TaskAssignmentServiceImpl#schedule` 只查任务状态，不查 `task_assignment`
> - **Hypothesis Impact**: Confirms hypothesis "重复派工窗口存在"
> - **Scope**: 单任务排程入口

#### Technical Solutions
> **Solution**: 在查询/入口层新增“待排任务标准化”能力，统一完成空值过滤、按 `earliestStart + batchId + sequence + taskId` 稳定排序、以及已存在派工记录的任务过滤。
> - **Status**: Proposed
> - **Problem**: READY 任务输入集合不可预测，存在重复派工和无意义计算风险。
> - **Rationale**: 把输入清洗前置，可减少 Calculator 对脏输入的依赖，且单任务/全量入口可复用。
> - **Alternatives**: 仅在 Calculator 内继续加强比较器；仅靠数据库状态避免重复派工。
> - **Evidence**: `product-pps/src/main/java/com/product/pps/service/impl/TaskSchedulingQueryService.java`，`product-pps/src/main/java/com/product/pps/service/impl/TaskAssignmentServiceImpl.java`
> - **Next Action**: 实现查询层/入口层标准化方法，并补单元测试。

#### Analysis Results
- 当前缺口与 `TODO.md` 的“待排任务排序与边界行为”完全对齐。
- 现有测试已覆盖策略排序，但尚未覆盖 READY 列表标准化和重复派工过滤。
- 可以在不触碰数据库结构的前提下完成这一轮优化。

#### Corrected Assumptions
- ~~只要 `taskComparator` 稳定就不会有排序问题~~ → 查询层和入口层仍然可能把无效或重复任务送进排程。
  - Reason: 比较器只能决定已有列表顺序，不能处理脏数据来源。

#### Open Items
- 是否已有 Mapper/Service 可直接判断任务是否存在有效派工记录。
- 是否需要给单任务入口返回更明确的失败原因，还是保持现有布尔语义。

#### Narrative Synthesis
**起点**: 基于 `TODO.md` 的多项待办，本轮需要先锁定一个可落地目标。  
**关键进展**: 已确认真正缺口在排程输入标准化，而非继续堆策略。  
**决策影响**: 分析方向从“继续做更复杂策略”调整为“先让输入集合稳定可靠”。  
**当前理解**: 当前轮次应落地 READY 任务排序、去重、重复派工过滤与边界保护。  
**遗留问题**: 需要补看派工记录判断方式并设计测试覆盖。  

## Decision Trail

### Critical Decisions
- 先实现 `product-pps` 的“待排任务排序与边界行为”，暂不扩展资源模型。
- 将 READY 任务标准化放在 `TaskSchedulingQueryService`，而不是继续堆叠到 `TaskSchedulingCalculator`。
- 单任务入口复用同一套标准化逻辑，避免同步/单任务行为分叉。

### Direction Changes
- 从“继续补复杂排程策略”切换到“先稳定排程输入集合”，原因是后者更贴近当前代码缺口且能单轮交付。

### Trade-offs Made
- 保持 `schedule` / `scheduleAll` 现有布尔返回，不额外扩展错误码或响应结构。
- 本轮只做查询层与入口层行为修复，不动数据库结构和资源模型。

## Synthesis & Conclusions

### Intent Coverage Matrix
| # | Original Intent | Status | Where Addressed | Notes |
|---|----------------|--------|-----------------|-------|
| 1 | 基于 `docs/TODO.md` 完成接下来的优化点 | 🔀 Transformed | Round 1 + 本轮实现 | 将“大而泛”的优化点收敛为当前最可落地的 `product-pps` 排程输入标准化 |

### Findings Coverage Matrix
| # | Finding (Round) | Disposition | Target |
|---|----------------|-------------|--------|
| 1 | READY 任务查询没有稳定顺序和已派工过滤 (R1) | recommendation | Rec #1 |
| 2 | Calculator 不能替代输入标准化 (R1) | absorbed | → Rec #1 |
| 3 | 单任务入口存在重复派工窗口 (R1) | recommendation | Rec #2 |

### Executive Summary
- 已完成 `product-pps` 待排任务排序与边界行为优化。
- READY 任务在进入排程前会做空值过滤、非 READY 过滤、按 `taskId` 去重、已派工记录排除，并按稳定比较链排序。
- 单任务排程入口已复用标准化逻辑，避免状态为 READY 但已存在派工记录的任务再次进入排程。
- 新增单元测试覆盖排序稳定性、重复任务过滤、已派工过滤和空输入场景。

### Key Conclusions
1. 查询层输入标准化是当前排程稳定性的关键控制点，优先级高于继续堆复杂策略。
2. 将边界保护前置后，`TaskSchedulingCalculator` 可以继续专注策略与时间计算职责。
3. 当前优化项已形成闭环，并与 `docs/TODO.md` 同步为已完成状态。

### Recommendations
1. 继续推进 `product-pps` / `product-execute` 的核心测试，覆盖排程失败、重复派工冲突、跨班次等分支。
2. 后续再进入资源模型扩展，把模具、人员/工位约束接入与本轮稳定输入集合对齐。

### Recommendation Review Summary
| # | Action | Priority | Steps | Review Status | Notes |
|---|--------|----------|-------|---------------|-------|
| 1 | 完成待排任务输入标准化与边界保护 | high | 3 | ✅ Accepted | 本轮已执行 |

## Plan Checklist

> **Execution completed in the same session.**

- **Recommendations**: 1
- **Generated**: 2026-04-21T00:00:00+08:00

### 1. 完成待排任务输入标准化与边界保护
- **Priority**: high
- **Rationale**: 让 READY 任务集合可预测、可去重、可解释，降低重复派工和排序漂移风险
- **Target files**: product-pps/src/main/java/com/product/pps/service/impl/TaskSchedulingQueryService.java, product-pps/src/main/java/com/product/pps/service/impl/TaskAssignmentServiceImpl.java, product-pps/src/test/java/com/product/pps/service/impl/TaskSchedulingQueryServiceTest.java
- **Acceptance criteria**: READY 任务进入排程前已过滤无效输入；已派工任务不会重复进入单任务排程；新增测试通过
- [x] Ready for execution
