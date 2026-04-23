# Analysis Discussion

**Session ID**: ANL-2026-04-22-docs-todo-md-继续完善优化方案
**Topic**: 基于 `docs/TODO.md` 继续完善项目优化方案
**Started**: 2026-04-22T10:30:00+08:00
**Dimensions**: architecture, implementation, performance, decision
**Depth**: standard

## Table of Contents
- [Analysis Context](#analysis-context)
- [Current Understanding](#current-understanding)
- [Discussion Timeline](#discussion-timeline)
- [Decision Trail](#decision-trail)
- [Synthesis & Conclusions](#synthesis--conclusions)

## Current Understanding

### What We Established
- `docs/TODO.md` 中“抽象排程策略，支持策略切换”已基本完成，`TaskAssignmentDTO`、`SchedulingStrategy`、`TaskSchedulingCoordinator`、`TaskSchedulingCalculator` 已支持 `EARLIEST_START`、`EARLIEST_FINISH`、`DUE_DATE_PRIORITY` 三种策略。
- 剩余高价值优化点不再是“继续加一个 enum”，而是把资源协同约束、任务执行闭环、订单状态守卫、领域模型字段承载补齐，否则现有排程策略只能在“单资源 + 单任务时长”模型内运行。
- 测试并非完全空白，`product-pps` 已覆盖策略排序与 READY 任务标准化，`product-execute` 已覆盖任务事件记录，但跨模块状态联动、资源约束和边界场景仍缺失。

### What Was Clarified
- ~~排程策略仍停留在 TODO 阶段~~ → 代码已经落地 3 种策略，TODO 更适合改写为“扩展策略评分模型与成本模型”。
- ~~任务事件闭环完全未开始~~ → 任务状态修改和事件日志记录已具备雏形，但尚未向批次、订单、资源状态延伸。

### Key Insights
- 当前最需要完善的是“从局部能力到业务闭环”的链路，而不是继续零散加点功能。
- `docs/TODO.md` 更适合从“功能清单”升级为“分阶段 roadmap”，把“已完成 / 已部分完成 / 待落地”分开，避免团队误判真实进度。

## Analysis Context
- Focus areas: 剩余 P1/P2 项重新收敛、排程资源模型、任务事件闭环、订单状态流转、领域模型支撑、测试缺口
- Perspectives: Technical, Architectural, Performance
- Depth: standard
- Prior session: `ANL-2026-04-21-docs-todo-md-完成接下来的优化点`

## Initial Questions
- 现有 TODO 中哪些事项已经被代码实现但仍未从文档上摘除？
- 哪些事项需要先补领域模型和状态机，才值得继续扩展排程算法？
- 下一轮开发如果要保持低风险，应该按什么顺序切分成可交付子任务？

## Initial Decisions
> **Decision**: 本轮不继续追单点实现，而是重新整理 `docs/TODO.md` 的剩余优化范围与执行顺序。
> - **Context**: 上一轮已经闭环 READY 任务标准化，但文档中的多数条目仍是粗粒度描述。
> - **Options considered**: 继续直接实现某个 P1 子项；只做文档润色；先核对代码现状后重构优化计划。
> - **Chosen**: 先核对代码现状后重构优化计划。 — **Reason**: 当前多项 TODO 已部分落地，如果不先校准事实，会导致后续排期和 issue 拆分失真。
> - **Rejected**: 直接实现某个 P1 子项会跳过优先级重排；只做文档润色无法给出可信的下一步执行边界。
> - **Impact**: 本轮输出以“事实校准 + 推荐拆分 + 执行顺序”为主，不修改业务代码。

---

## Discussion Timeline

### Round 1 - Explore Remaining TODO (2026-04-22T10:45:00+08:00)

#### User Input
用户说明 `docs/TODO.md` 是项目目前的优化方案，要求继续完善。

#### Decision Log
> **Decision**: 将“继续完善”解释为“基于代码现状，重写剩余优化项的优先级与实施边界”。
> - **Context**: 当前 TODO 同时混杂已完成项、未完成项和较大的抽象方向。
> - **Options considered**: 按原表逐条照抄；只关注 `product-pps`；跨模块核对后重构建议。
> - **Chosen**: 跨模块核对后重构建议。 — **Reason**: `product-pps`、`product-execute`、`product-demand`、`product-domain` 已出现联动关系，单模块视角会漏掉真实瓶颈。
> - **Rejected**: 逐条照抄不能反映代码现状；只看 `product-pps` 会忽略事件闭环和订单状态边界。
> - **Impact**: 结论将按“已落地 / 部分落地 / 真缺口”重组。

#### Key Findings
> **Finding**: 排程策略抽象已落地，`TaskAssignmentDTO#scheduleStrategy`、`SchedulingStrategy` 和 `TaskSchedulingCalculator#orderTasks` 已共同支撑 3 种策略。
> - **Confidence**: High — **Why**: 直接代码证据来自 `product-domain/src/main/java/com/product/domain/dto/TaskAssignmentDTO.java`、`product-pps/src/main/java/com/product/pps/enums/SchedulingStrategy.java`、`product-pps/src/main/java/com/product/pps/service/impl/TaskSchedulingCalculator.java`
> - **Hypothesis Impact**: Refutes hypothesis "排程策略仍是待实现主项"
> - **Scope**: `product-pps` 策略层、`docs/TODO.md` 文档准确性

> **Finding**: 资源模型仍停留在“机台 + 班次”主路径，`TaskSchedulingCalculator#chooseMachine` 只基于 `Resource`、`Calendar`、`stdDurationMin` 计算，没有模具、人员、工位或换型矩阵参与。
> - **Confidence**: High — **Why**: 直接代码证据来自 `product-pps/src/main/java/com/product/pps/service/impl/TaskSchedulingCalculator.java`、`product-domain/src/main/java/com/product/domain/entity/Resource.java`
> - **Hypothesis Impact**: Confirms hypothesis "资源协同约束是真正未闭环的核心缺口"
> - **Scope**: `product-pps`、`product-domain`

> **Finding**: 任务事件服务已具备“更新任务状态 + 记录事件”的闭环雏形，但 `TaskEventServiceImpl` 仅更新 `OperationTask`，没有同步 `ProductionBatch`、`OrderLine`、`CustomerOrder` 或资源状态。
> - **Confidence**: High — **Why**: 直接代码证据来自 `product-execute/src/main/java/com/product/execute/service/impl/TaskEventServiceImpl.java`
> - **Hypothesis Impact**: Modifies hypothesis "执行闭环完全缺失"
> - **Scope**: `product-execute`、`product-demand`、`product-domain`

> **Finding**: 订单状态守卫存在但不完整，`CustomerOrderServiceImpl#check` / `cancelCheck` 阻止已投产或已完成订单被确认/反确认，但 `updateCustomerOrder` 仍可直接更新订单，未统一走状态机。
> - **Confidence**: High — **Why**: 直接代码证据来自 `product-demand/src/main/java/com/product/demand/service/impl/CustomerOrderServiceImpl.java`
> - **Hypothesis Impact**: Confirms hypothesis "订单状态入口约束未统一"
> - **Scope**: `product-demand`

> **Finding**: 测试基础已存在，但目前集中在纯函数或局部服务，尚未覆盖资源约束、事件驱动状态联动、跨班次闭环等集成场景。
> - **Confidence**: High — **Why**: 直接代码证据来自 `product-pps/src/test/java/com/product/pps/service/impl/TaskSchedulingCalculatorTest.java`、`product-pps/src/test/java/com/product/pps/service/impl/TaskSchedulingQueryServiceTest.java`、`product-execute/src/test/java/com/product/execute/service/impl/TaskEventServiceImplTest.java`
> - **Hypothesis Impact**: Modifies hypothesis "核心测试尚未开始"
> - **Scope**: `product-pps`、`product-execute`

#### Technical Solutions
> **Solution**: 将 TODO 重构为 4 个连续阶段：事实校准、资源约束建模、执行状态闭环、验证与文档收口。
> - **Status**: Proposed
> - **Problem**: 当前 TODO 把已完成事项与真正缺口混写，导致优先级和实施边界不清晰。
> - **Rationale**: 先校准事实，再补领域模型和状态机，最后扩测试与文档，能降低跨模块返工。
> - **Alternatives**: 继续按模块平铺任务；只围绕 `product-pps` 扩功能。
> - **Evidence**: `docs/TODO.md`，`product-pps/src/main/java/com/product/pps/service/impl/TaskSchedulingCalculator.java`，`product-execute/src/main/java/com/product/execute/service/impl/TaskEventServiceImpl.java`，`product-demand/src/main/java/com/product/demand/service/impl/CustomerOrderServiceImpl.java`
> - **Next Action**: 输出分阶段建议与验收标准，并生成后续 handoff。

> **Solution**: 资源约束扩展优先补“任务资源需求 + 资源兼容关系 + 换型时间来源”，再考虑“最低成本”等新策略。
> - **Status**: Validated
> - **Problem**: 没有资源需求模型时，新增策略只会在错误抽象上继续叠加复杂度。
> - **Rationale**: 先让算法有足够输入，再讨论优化目标函数，能避免策略枚举膨胀。
> - **Alternatives**: 先新增 `LOWEST_COST` 策略；先做更多排序规则。
> - **Evidence**: `product-domain/src/main/java/com/product/domain/entity/OperationTask.java`，`product-domain/src/main/java/com/product/domain/entity/Resource.java`，`product-domain/src/main/java/com/product/domain/entity/Machine.java`
> - **Next Action**: 为 `product-domain` 增补资源需求和换型相关字段或关系表设计。

#### Analysis Results
- `docs/TODO.md` 中至少 2 项应改判为“已完成或部分完成”：排程策略抽象、核心测试起步。
- 真正高优先级的未完成项是资源协同约束与执行状态闭环，这两项决定后续排程结果是否可信、订单状态是否可追踪。
- `product-domain` 当前实体能承载“机台、任务、事件、订单”的基础信息，但不足以表达资源兼容矩阵、辅助资源占用、换型成本来源。
- `TaskEventServiceImpl` 适合作为状态闭环入口，因为它已经承担“状态变更 + 事件记录”的事务边界；后续应在这里扩展批次、订单、资源联动，而不是把联动散落到 controller。

#### Corrected Assumptions
- ~~TODO 中的 P1 顺序可以直接照搬执行~~ → 需要先把“已完成/部分完成”剥离，再按依赖顺序重排。
  - Reason: 否则会把已经落地的策略抽象再次排入主线。
- ~~先扩展更多策略比补资源模型更重要~~ → 资源模型缺口才是当前算法可信度上限。
  - Reason: 现有算法连模具、人员、工位占用都看不到，策略扩展收益有限。

#### Open Items
- `product-domain` 应采用新增字段、关联表还是 DTO 聚合来表达任务对模具/人员/工位的约束。
- 批次、订单、订单行的状态推进规则要放在 `TaskEventServiceImpl` 统一编排，还是抽成独立 domain service。
- 文档是否直接回写 `docs/TODO.md`，还是先生成 handoff 再由后续实现轮次同步。

#### Narrative Synthesis
**起点**: 基于上一轮已经完成的 READY 任务标准化，本轮目标是继续完善剩余优化方案。  
**关键进展**: 已确认当前瓶颈从“排程输入稳定性”转移到“资源约束建模 + 执行状态闭环”。  
**决策影响**: 分析方向从单点功能推进调整为跨模块 roadmap 重构。  
**当前理解**: 后续优化不应再把“策略切换”当成主任务，而应先补模型和状态链路。  
**遗留问题**: 还需要决定资源关系如何落库，以及状态机由谁统一编排。  

#### Intent Coverage Check
- ✅ Intent 1: 继续完善 `docs/TODO.md` 对应的优化方案 —— 已完成事实校准与重新分阶段。
- ✅ Intent 2: 给出更可执行的后续方向 —— 已形成优先级和拆分建议。

## Decision Trail

### Critical Decisions
- 将本轮目标定义为“完善优化方案”，而不是继续直接改业务代码。
- 将剩余主线重排为“资源约束建模 → 执行状态闭环 → 测试与文档收口”。
- 将 `TaskEventServiceImpl` 视为执行闭环的主要扩展入口，而不是在多个 controller/service 中分散联动。

### Direction Changes
- 从“继续实现更多排程策略”切换到“先补资源模型和状态链路”，因为前者已部分完成，后者才是当前真实短板。

### Trade-offs Made
- 本轮只输出分析结论和 handoff，不直接修改 `docs/TODO.md` 或业务代码。
- 暂不引入外部调度框架或复杂优化算法，优先在现有 Spring Boot + MyBatis-Plus 结构内补齐领域能力。

## Synthesis & Conclusions

### Intent Coverage Matrix
| # | Original Intent | Status | Where Addressed | Notes |
|---|----------------|--------|-----------------|-------|
| 1 | 基于 `docs/TODO.md` 继续完善项目优化方案 | ✅ Addressed | Round 1, Recommendations | 已完成事实校准、缺口重排、执行顺序重构 |

### Findings Coverage Matrix
| # | Finding (Round) | Disposition | Target |
|---|----------------|-------------|--------|
| 1 | 排程策略抽象已基本落地 (R1) | recommendation | Rec #1 |
| 2 | 资源协同约束仍未进入算法主路径 (R1) | recommendation | Rec #2 |
| 3 | 任务事件未联动批次/订单/资源状态 (R1) | recommendation | Rec #3 |
| 4 | 订单状态守卫未统一走状态机 (R1) | recommendation | Rec #3 |
| 5 | 测试已有基础但缺跨模块场景 (R1) | recommendation | Rec #4 |

### Executive Summary
- 当前优化方案需要先做“事实校准”：排程策略切换和部分测试已落地，不宜继续当成核心未完成项。
- 真正需要优先推进的是资源约束建模和执行状态闭环，这两项决定排程结果是否可信、业务状态是否一致。
- 后续实施宜按“文档重构 -> 领域建模 -> 事件联动 -> 测试与文档收口”分阶段推进。

### Key Conclusions
1. `product-pps` 的下一阶段价值不在新增策略枚举，而在让算法读取到更真实的资源占用与换型约束。
2. `product-execute` 已经具备状态事务入口，应向批次、订单、资源状态联动扩展，形成单一闭环。
3. `docs/TODO.md` 应升级为 roadmap，而不是继续堆叠“是否已完成不明确”的条目。

### Recommendations
1. **重写 TODO 为分阶段 roadmap** `[high]`
   - Rationale: 先区分“已完成 / 部分完成 / 待落地”，后续迭代才不会重复投入。
   - Evidence: `docs/TODO.md`，`product-pps/src/main/java/com/product/pps/enums/SchedulingStrategy.java`
   - Steps:
     - `docs/TODO.md`: 将“抽象排程策略”改为已完成，将“核心测试”改为部分完成，并新增“待扩展策略评分模型”。
     - `docs/TODO.md`: 把剩余事项拆成阶段性章节，而不是单张平铺表。
     - Verification: 文档能明确看出每项的真实状态、依赖关系和后续入口。
2. **优先补资源约束领域模型，再扩调度目标函数** `[high]`
   - Rationale: 没有任务资源需求、兼容关系、换型时间来源，`LOWEST_COST` 等策略会建立在错误抽象上。
   - Evidence: `product-pps/src/main/java/com/product/pps/service/impl/TaskSchedulingCalculator.java`，`product-domain/src/main/java/com/product/domain/entity/OperationTask.java`，`product-domain/src/main/java/com/product/domain/entity/Resource.java`
   - Steps:
     - `product-domain`: 为任务和资源增加模具/人员/工位需求、兼容关系、换型来源字段或关系对象。
     - `product-pps`: 让 `TaskSchedulingQueryService` 加载资源兼容上下文，`TaskSchedulingCalculator` 在选机台前校验协同资源占用。
     - Verification: 排程结果能解释为什么某任务因模具/工位/人员限制被延后或不可排。
3. **把任务事件扩成业务状态闭环入口** `[high]`
   - Rationale: 当前事件日志和任务状态已在同一事务边界，适合继续联动批次、订单行、订单、资源状态。
   - Evidence: `product-execute/src/main/java/com/product/execute/service/impl/TaskEventServiceImpl.java`，`product-demand/src/main/java/com/product/demand/service/impl/CustomerOrderServiceImpl.java`
   - Steps:
     - `product-execute`: 在 `TaskEventServiceImpl` 中抽出状态推进编排，事件完成后同步批次、订单行、订单状态。
     - `product-demand`: 将订单可编辑/可确认/可取消判断收敛到统一状态守卫，避免 `updateCustomerOrder` 旁路。
     - Verification: 完工、暂停、恢复等事件会驱动批次和订单状态变化，且非法修改入口被阻止。
4. **把测试从局部单测补到关键业务链路** `[medium]`
   - Rationale: 现有测试能证明局部逻辑正确，但不能证明跨模块状态一致性。
   - Evidence: `product-pps/src/test/java/com/product/pps/service/impl/TaskSchedulingCalculatorTest.java`，`product-execute/src/test/java/com/product/execute/service/impl/TaskEventServiceImplTest.java`
   - Steps:
     - `product-pps/src/test/java`: 补资源约束、跨班次、无协同资源的失败场景。
     - `product-execute/src/test/java` 与 `product-demand/src/test/java`: 补任务事件驱动批次/订单状态联动测试。
     - Verification: 关键链路在测试中可复现实例，并能对状态流转给出稳定断言。

### Recommendation Review Summary
| # | Action | Priority | Steps | Review Status | Notes |
|---|--------|----------|-------|---------------|-------|
| 1 | 重写 TODO 为分阶段 roadmap | high | 3 | ✅ Accepted | 作为下一轮文档与实施入口 |
| 2 | 优先补资源约束领域模型，再扩调度目标函数 | high | 3 | ✅ Accepted | 作为排程主线任务 |
| 3 | 把任务事件扩成业务状态闭环入口 | high | 3 | ✅ Accepted | 作为执行链路主线任务 |
| 4 | 把测试从局部单测补到关键业务链路 | medium | 2 | ✅ Accepted | 跟随主线一起补回归保护 |

## Plan Checklist

> **This is a plan only — no code was modified.**

- **Recommendations**: 4
- **Generated**: 2026-04-22T11:10:00+08:00

### 1. 重写 docs/TODO.md 为分阶段 roadmap
- **Priority**: high
- **Rationale**: 先把已完成、部分完成、待落地项分开，避免后续任务重复投入。
- **Target files**: `docs/TODO.md`
- **Acceptance criteria**: 文档明确区分真实状态；按阶段展示依赖与验收口径；后续任务入口清晰。
- [ ] Ready for execution

### 2. 补资源约束领域模型与排程上下文
- **Priority**: high
- **Rationale**: 没有资源需求与兼容关系，现有排程只能在简化模型下运行。
- **Target files**: `product-domain`，`product-pps/src/main/java/com/product/pps/service/impl/TaskSchedulingQueryService.java`，`product-pps/src/main/java/com/product/pps/service/impl/TaskSchedulingCalculator.java`
- **Acceptance criteria**: 任务和资源可表达模具/人员/工位约束；排程计算会考虑协同资源；结果可解释延后原因。
- [ ] Ready for execution

### 3. 扩展 TaskEvent 驱动的业务状态闭环
- **Priority**: high
- **Rationale**: 当前事件已能更新任务状态并记日志，应继续联动批次、订单行、订单和资源状态。
- **Target files**: `product-execute/src/main/java/com/product/execute/service/impl/TaskEventServiceImpl.java`，`product-demand/src/main/java/com/product/demand/service/impl/CustomerOrderServiceImpl.java`
- **Acceptance criteria**: 开工/暂停/恢复/完工事件驱动业务状态变化；非法订单修改入口受守卫约束。
- [ ] Ready for execution

### 4. 补关键链路测试
- **Priority**: medium
- **Rationale**: 需要用测试保护新增资源约束和状态闭环。
- **Target files**: `product-pps/src/test/java`，`product-execute/src/test/java`，`product-demand/src/test/java`
- **Acceptance criteria**: 排程约束场景、跨班次、事件联动与状态守卫都有回归测试。
- [ ] Ready for execution

### Session Statistics
- Total rounds: 1
- Key findings: 5
- Dimensions covered: 4
- Artifacts generated: `discussion.md`, `exploration-codebase.json`, `conclusions.json`
- Decision count: 4
