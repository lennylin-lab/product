# 成本模型精度提升 — Design

## 成本口径（精确定义）

`compositeCost = wSetup×setupCostMin + wChangeover×changeoverCount + wCrossShift×crossShiftCount + wEnergy×energyFactor`

- `setupCostMin`：现有 `estimateSetupCost`（需求声明 → 机台默认 → 0），量纲分钟。
- `changeoverCount`：候选机台在**本次排程运行内**已执行的换模次数（数据源：
  `MachineLastAssignment` 链 + `ResourceRuntimeContext`，实现时以两处实际字段为准），
  本次选择若再触发换模则计 1 次；首任务无历史 → 0。
- `crossShiftCount`：候选窗口 `[plannedStart, plannedEnd)` 跨越的班次边界数
  （从所选日历的班次切分派生；窗口全在同一班次内 → 0）。
- `energyFactor`：恒 0（KD1 预留；master-data 有能耗字段后接入）。

默认权重（业务可读基准，可配置）：`wSetup=1`、`wChangeover=30`（一次额外换模 ≈ 30 分钟
等效成本）、`wCrossShift=60`（一次跨班 ≈ 60 分钟等效成本）、`wEnergy=0`。

## 配置（KD2）

`ProductCostModelProperties`（`@ConfigurationProperties("product.pps.schedule.cost-model")`）：
`setup-weight / changeover-count-penalty / cross-shift-penalty / energy-weight`，
yml 默认 + env 覆盖（`PRODUCT_PPS_SCHEDULE_COST_MODEL_*`）。绑定测试覆盖默认值与覆盖值。
权重可为 0（因子关闭），不允许负值（绑定校验，负值启动失败）。

## 接入点

- 仅 LOWEST_COST：`machineChoiceComparator` 主键从 `setupCostMin` 换为 `compositeCost`
  （MachineChoice 增加 `compositeCost` 字段，`chooseBestMachine` 逐候选计算）；平局键
  plannedEnd/plannedStart/machineId 与 `sameMoldPreferred` 前置规则不变。
- `estimateSetupCost` 保留原职责（= 综合成本的换型时间分量来源），不修改。
- 其余三策略比较器、任务级排序、选择级联、时间计算：零改动。

## Validation & Error Matrix

| 条件 | 行为 |
|------|------|
| 非LOWEST_COST 策略 | 成本因子完全不参与（比较器不变） |
| 权重全默认 + 无换模历史 + 窗口不跨班 | compositeCost == setupCostMin（既有用例结果不变） |
| 权重配置负值 | 启动失败（绑定校验） |
| 候选无日历/无机台扩展 | 对应因子按 0 计（沿用现有空值口径） |

## Tests Required

1. 因子单测：换模次数派生（有/无历史、多链）、跨班次计数（0/1/多次、窗口恰在边界）。
2. 比较器：各因子主导的排序场景 + 权重全默认时与现状等价（既有 LOWEST_COST 用例零回归）。
3. 配置绑定：默认值、yml 覆盖、env 覆盖、负值拒绝。
4. IT（compose+REST 套件新增）：换模历史驱动的选择偏移场景（LOWEST_COST 下两台机台
   因换模次数不同而选择反转）+ 默认策略场景不回归；同栈连跑两轮全绿。
5. 回归：270 离线 + 10 IT 基线全绿；单体 29/29 编译。

## Wrong vs Correct

#### Wrong
- 触碰其他三策略或时间计算；为能耗新增 DDL（KD1 预留即可）；权重硬编码；负权重静默取 0。
- 用绝对成本值改变任务级排序（任务排序仍按 task.changeoverTimeMin，本任务不动）。

#### Correct
- 综合成本只是 LOWEST_COST 机台比较器的一个更好的主键：因子可配置、派生可测、
  默认配置下与现状可对照。
