# Analysis Discussion

**Session ID**: ANL-2026-04-25-docs-todo-md项目进度检查
**Topic**: `docs/TODO.md` 查看项目当前进度，判断 todo 列表是否需要更新
**Started**: 2026-04-25T19:09:08+08:00
**Dimensions**: implementation, architecture, decision
**Depth**: standard

## Table of Contents
- [Analysis Context](#analysis-context)
- [Current Understanding](#current-understanding)
- [Discussion Timeline](#discussion-timeline)
- [Decision Trail](#decision-trail)
- [Synthesis & Conclusions](#synthesis--conclusions)

## Current Understanding

### What We Established
- `docs/TODO.md` 需要更新，而且不是小修小补，而是按真实状态重写。
- `product-pps` 的“抽象排程策略，支持策略切换”已经落地，不应继续保留在待开发表中。
- `product-pps` 的 READY 任务标准化与稳定排序已落地，且已有测试覆盖。
- `product-execute` 的任务事件基础闭环已具备，包含 `start/pause/resume/complete` 和事件记录，但尚未联动批次、订单、资源状态。
- `product-demand` 的订单状态守卫已有基础实现，但仍未形成统一状态机和跨模块闭环。

### What Was Clarified
- ~~排程策略仍是待开发项~~ → 已支持 `EARLIEST_START`、`EARLIEST_FINISH`、`DUE_DATE_PRIORITY` 3 种策略。
- ~~核心测试仍是空白~~ → `product-pps` 与 `product-execute` 已有 3 个核心测试文件，但覆盖范围仍偏局部。
- ~~需求说明书完全未推进~~ → 文档已有骨架，但离“可验收文档”还有较大差距。

### Key Insights
- 当前主要缺口已经从“有没有这个能力”转向“能力是否形成跨模块业务闭环”。
- `docs/TODO.md` 当前混杂“已完成”“部分完成”“真正待做”三种状态，容易误导后续排期。

## Analysis Context
- Focus areas: `docs/TODO.md` 真实状态校准、排程能力进度、执行闭环进度、测试覆盖、文档完成度
- Perspectives: Technical, Architectural
- Depth: standard
- Prior session: `ANL-2026-04-22-docs-todo-md-继续完善优化方案`

## Initial Questions
- `docs/TODO.md` 中哪些任务已完成但仍未移出待办？
- 哪些任务只是“部分完成”，需要改写验收口径？
- 当前最真实的后续优先级应该如何排序？

## Initial Decisions
> **Decision**: 本轮聚焦“事实校准”，不继续沿用原 TODO 的状态描述。
> - **Context**: 近 7 天内已有与 TODO 相关的实现和测试提交，且已有历史分析指出文档滞后。
> - **Options considered**: 只看 `docs/TODO.md`；重新扫描代码后判断；直接改 TODO。
> - **Chosen**: 重新扫描代码后判断。 — **Reason**: 用户先要求判断是否需要更新，必须先基于证据确认真实进度。
> - **Rejected**: 只看文档会重复旧结论；直接改 TODO 会跳过事实核验。
> - **Impact**: 本轮输出以“进度判断 + TODO 更新建议”为主，不修改业务代码。

---

## Discussion Timeline

### Round 1 - Codebase Progress Audit (2026-04-25T19:09:08+08:00)

#### User Input
用户要求基于 `docs/TODO.md` 判断项目当前进度，并确认 todo 列表是否需要更新。

#### Decision Log
> **Decision**: 本轮直接复用近 7 天会话与代码证据，不再做泛化的全仓扫描。
> - **Context**: 已存在 2026-04-22 的相关分析会话，且本周有明确提交 `42d135a`、`b99c022`。
> - **Options considered**: 全量重新分析；仅看当前文档；结合历史分析与本周代码增量。
> - **Chosen**: 结合历史分析与本周代码增量。 — **Reason**: 能快速获得最接近“当前进度”的结论，而且证据更完整。
> - **Rejected**: 全量重扫成本高且信息重复；只看文档无法判断状态是否陈旧。
> - **Impact**: 结论将同时参考文档、代码、测试、近期提交。

#### Key Findings
> **Finding**: 排程策略切换已经完成，不应继续放在开发任务表的 P1 待办中。
> - **Confidence**: High — **Why**: `SchedulingStrategy` 已定义 3 种策略，`TaskSchedulingCoordinator` 与 `TaskSchedulingCalculator` 已按参数分支执行。
> - **Hypothesis Impact**: Refutes hypothesis "排程策略仍是主要未完成功能"
> - **Scope**: `product-pps`，`docs/TODO.md`

> **Finding**: READY 任务排序与边界处理已经完成，并有测试覆盖。
> - **Confidence**: High — **Why**: `TaskSchedulingQueryService#normalizeReadyTasksForScheduling` 已实现过滤、去重、排除已派工、稳定排序；`TaskSchedulingQueryServiceTest` 已验证。
> - **Hypothesis Impact**: Confirms hypothesis "TODO 中存在已完成但未正确收敛的条目"
> - **Scope**: `product-pps`，测试，`docs/TODO.md`

> **Finding**: 任务事件基础链路已具备，但“状态闭环”仍只是部分完成。
> - **Confidence**: High — **Why**: `TaskEventServiceImpl` 已支持开始、暂停、恢复、完工并记录事件；但未见批次/订单/资源状态联动。
> - **Hypothesis Impact**: Modifies hypothesis "任务事件闭环完全未开始"
> - **Scope**: `product-execute`，`product-demand`

> **Finding**: 核心测试不是空白，但 TODO 的验收口径需要改成“扩覆盖”，而不是笼统的“补测试”。
> - **Confidence**: High — **Why**: 存在 `TaskSchedulingCalculatorTest`、`TaskSchedulingQueryServiceTest`、`TaskEventServiceImplTest`。
> - **Hypothesis Impact**: Modifies hypothesis "核心测试仍待从零开始"
> - **Scope**: `product-pps`，`product-execute`，`docs/TODO.md`

> **Finding**: 需求说明书仍只有骨架，文档任务依旧有效。
> - **Confidence**: High — **Why**: `docs/生产计划与排程计划系统需求说明书.md` 仍标记“待补充”，缺角色、流程、验收标准等实质内容。
> - **Hypothesis Impact**: Confirms hypothesis "文档收口仍未完成"
> - **Scope**: `docs`

#### Technical Solutions
> **Solution**: 将 `docs/TODO.md` 从“平铺任务表”改成“状态化 roadmap”，至少拆为“已完成 / 部分完成 / 待落地”三段。
> - **Status**: Validated
> - **Problem**: 现有 TODO 无法准确反映真实进度，导致优先级误判。
> - **Rationale**: 当前已有多项能力落地，如果不区分状态，会重复投入已完成工作。
> - **Alternatives**: 保持原表仅微调；直接删除已完成项但保留原结构。
> - **Evidence**: `docs/TODO.md`，`product-pps/src/main/java/com/product/pps/enums/SchedulingStrategy.java`，`product-pps/src/test/java/com/product/pps/service/impl/TaskSchedulingQueryServiceTest.java`，`product-execute/src/test/java/com/product/execute/service/impl/TaskEventServiceImplTest.java`
> - **Next Action**: 把策略切换、READY 任务标准化移出待开发区；把任务事件闭环、测试补齐改写为“部分完成后的下一步”。

#### Analysis Results
- 本周提交 `42d135a` 对应待排任务进入排程前的过滤，和 TODO 中“修正待排任务排序与边界行为（已完成）”一致。
- 本周提交 `b99c022` 对应单元测试补充，说明“补核心测试”应改成“补跨模块与异常场景测试”。
- `TaskSchedulingCoordinator` 已具备进度推送阈值解耦与编排职责下沉，说明“优化排程编排与进度职责”至少是部分完成，而不是纯待做。
- `CustomerOrderServiceImpl#check` 已限制已投产与已完成订单被错误确认，但未见完整状态机约束，说明订单状态流转属于“已有守卫，未形成统一机制”。

#### Corrected Assumptions
- ~~TODO 中 P1 项大多尚未开始~~ → 多项已开始，部分已完成。
  - Reason: 当前代码、测试和近期提交都能直接证明进度。
- ~~测试目录几乎为空~~ → MCP 首轮目录读取遗漏了测试文件，后续通过语义检索与文件扫描确认已有测试文件。
  - Reason: 初次目录遍历参数没有命中深层测试文件。

#### Open Items
- 资源模型扩展是否已有数据库字段设计，当前证据只能确认代码层尚未进入排程主路径。
- 排程编排职责是否应从 TODO 中降级为“收尾优化项”。
- 是否需要把 `docs/TODO.md` 与 `docs/生产排程解决方案落地现状.md` 合并维护，避免重复。

#### Narrative Synthesis
**起点**: 基于用户对“当前进度是否需要同步到 TODO”的疑问，本轮从 `docs/TODO.md` 与本周代码增量切入。  
**关键进展**: 代码证据确认了排程策略切换、READY 任务标准化和部分测试已经落地，修正了 TODO 中多个过时状态。  
**决策影响**: 由于证据集中指向“文档滞后”，本轮分析方向保持在“状态校准”，没有扩展到新的功能设计。  
**当前理解**: 项目已经完成一批基础排程与执行能力，真正未完成的是资源约束建模、跨模块状态闭环、测试扩覆盖和需求文档收口。  
**遗留问题**: 下一步要决定是直接更新 `docs/TODO.md`，还是先把 roadmap 和 issue 结构一起重写。  

#### Intent Coverage Check
- ✅ Intent 1: 查看项目当前进度 — 已通过代码、测试、提交记录完成校准
- ✅ Intent 2: 判断 todo 列表是否需要更新 — 已明确需要更新
- 🔄 Intent 3: 更新到什么粒度最合适 — 已形成建议，但尚未执行文档改写

---

## Decision Trail

### Critical Decisions
- 采用“历史分析 + 本周代码增量”的方式做事实校准，而不是只看 TODO 文档。
- 将结论聚焦为“状态不准确导致的文档滞后”，而不是扩展成新的实现规划。

## Synthesis & Conclusions

### Intent Coverage Matrix
| # | Original Intent | Status | Where Addressed | Notes |
|---|----------------|--------|-----------------|-------|
| 1 | 查看项目当前进度 | ✅ Addressed | Round 1 | 已核对代码、测试、近 7 天提交 |
| 2 | 判断 TODO 是否需要更新 | ✅ Addressed | Round 1, Conclusion #1 | 结论为“需要更新” |
| 3 | 明确更新方向 | 🔀 Transformed | Conclusion #2-#4 | 从“是否更新”延伸为“按状态重写” |

### Findings Coverage Matrix
| # | Finding (Round) | Disposition | Target |
|---|----------------|-------------|--------|
| 1 | 排程策略切换已完成 (R1) | recommendation | Rec #1 |
| 2 | READY 任务排序与边界行为已完成 (R1) | recommendation | Rec #1 |
| 3 | 任务事件闭环仅部分完成 (R1) | recommendation | Rec #2 |
| 4 | 核心测试已有基础但范围有限 (R1) | recommendation | Rec #3 |
| 5 | 需求说明书仍缺可验收内容 (R1) | recommendation | Rec #4 |
| 6 | 编排与进度职责已部分下沉 (R1) | absorbed | → Rec #2 |

### Executive Summary
- 项目当前进度比 `docs/TODO.md` 表现出来的更靠前，至少“排程策略切换”“READY 任务标准化”“部分核心测试”已经落地。
- `docs/TODO.md` 需要更新，否则会把已完成事项继续当作未完成事项推进。
- 后续待办应从“新增基础能力”切换为“补闭环、补约束、补覆盖、补文档”。

### Key Conclusions
1. `docs/TODO.md` 当前不是简单过时，而是状态模型失真，已不适合作为项目进度总览。
2. `product-pps` 的核心基础能力已进入“优化和扩展”阶段，重点不再是“支持策略切换”，而是“让策略建立在真实资源约束之上”。
3. `product-execute` 与 `product-demand` 已各自具备局部状态能力，但跨模块状态闭环仍未完成。
4. 测试与需求文档都已有起点，后续任务应写成“扩覆盖 / 补验收”，而不是“从零开始”。

### Recommendations
1. **重写 `docs/TODO.md` 的状态结构** `[high]`
   - Rationale: 把“已完成 / 部分完成 / 待落地”分开，避免团队误判真实进度。
   - Evidence: `docs/TODO.md`，`product-pps/src/main/java/com/product/pps/enums/SchedulingStrategy.java`，`product-pps/src/main/java/com/product/pps/service/impl/TaskSchedulingQueryService.java`
   - Steps:
     - 将“抽象排程策略，支持策略切换”移到已完成。
     - 将“修正待排任务排序与边界行为（已完成）”并入已完成摘要，不再留在开发表中。
     - 将“补核心测试”改写为“补跨模块状态联动、异常场景、跨班次测试”。
2. **把执行闭环相关任务改写为“部分完成后的下一步”** `[high]`
   - Rationale: `TaskEventServiceImpl` 与 `CustomerOrderServiceImpl` 已有局部状态逻辑，真正缺的是跨模块联动。
   - Evidence: `product-execute/src/main/java/com/product/execute/service/impl/TaskEventServiceImpl.java`，`product-demand/src/main/java/com/product/demand/service/impl/CustomerOrderServiceImpl.java`
   - Steps:
     - 将“补齐任务事件与状态闭环”改成“联动批次 / 订单 / 资源状态”。
     - 将“规范订单状态流转与排程入口约束”改成“统一状态机和禁止非法回退/修改”。
     - 将“优化排程编排与进度职责”降级为收尾项或标记部分完成。
3. **保留资源模型扩展为核心 P1** `[high]`
   - Rationale: 这一项仍是决定排程可信度的主缺口，目前没有证据表明已进入算法主路径。
   - Evidence: `product-pps/src/main/java/com/product/pps/service/impl/TaskSchedulingCalculator.java`，`docs/生产排程解决方案落地现状.md`
   - Steps:
     - 保留“资源状态、协同约束、换型时间”作为主待办。
     - 在 TODO 中明确这是当前阶段最核心的算法输入缺口。
4. **保留需求说明书补全任务，但调整为文档收口项** `[medium]`
   - Rationale: 文档已有骨架，应该从“补骨架”改为“补可验收内容”。
   - Evidence: `docs/生产计划与排程计划系统需求说明书.md`
   - Steps:
     - 将文档任务表述为补角色、流程、功能清单、非功能需求、验收标准、边界条件。
     - 明确该项依赖前述状态闭环与资源模型的事实收敛。

### Recommendation Review Summary
| # | Action | Priority | Steps | Review Status | Notes |
|---|--------|----------|-------|---------------|-------|
| 1 | 重写 `docs/TODO.md` 的状态结构 | high | 3 | ✅ Accepted | |
| 2 | 把执行闭环相关任务改写为“部分完成后的下一步” | high | 3 | ✅ Accepted | |
| 3 | 保留资源模型扩展为核心 P1 | high | 2 | ✅ Accepted | |
| 4 | 保留需求说明书补全任务，但调整为文档收口项 | medium | 2 | ✅ Accepted | |

### Session Statistics
- Total rounds: 1
- Key findings: 6
- Dimensions covered: 3
- Artifacts generated: 3
- Decision count: 2

## Plan Checklist

> **This is a plan only — no code was modified during the analysis phase.**

- **Recommendations**: 4
- **Generated**: 2026-04-25T19:09:08+08:00

### 1. 重写 `docs/TODO.md` 的状态结构
- **Priority**: high
- **Rationale**: 把已完成、部分完成、待落地分开，避免重复投入。
- **Target files**: `docs/TODO.md`
- **Acceptance criteria**: 文档可直接看出当前真实进度；已完成项不再残留在待开发区；待办表述聚焦真实缺口。
- [x] Ready for execution

### 2. 改写执行闭环与订单状态待办
- **Priority**: high
- **Rationale**: 当前缺口是跨模块联动，不是从零补任务事件或订单守卫。
- **Target files**: `docs/TODO.md`
- **Acceptance criteria**: 待办描述明确体现批次、订单、资源状态联动与状态机约束。
- [x] Ready for execution

### 3. 保留资源模型扩展为核心 P1
- **Priority**: high
- **Rationale**: 资源协同约束仍是当前排程可信度的核心瓶颈。
- **Target files**: `docs/TODO.md`
- **Acceptance criteria**: 文档优先级反映资源约束是当前第一缺口。
- [x] Ready for execution

### 4. 保留需求说明书补全任务为文档收口项
- **Priority**: medium
- **Rationale**: 文档已有骨架，后续应补齐可验收内容。
- **Target files**: `docs/TODO.md`，`docs/生产计划与排程计划系统需求说明书.md`
- **Acceptance criteria**: 文档任务描述从“起草”转为“收口与验收”。
- [x] Ready for execution
