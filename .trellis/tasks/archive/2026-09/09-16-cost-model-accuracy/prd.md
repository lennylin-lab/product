# 成本模型精度提升

## Goal

LOWEST_COST 排程策略的机台选择成本从单一换型时间扩展为加权综合成本（换型时间 + 换模次数惩罚 + 跨班次损耗，能耗预留），因子全部从现有数据派生（零 DDL），权重经服务配置可调。

## Confirmed Facts（仓库证据，2026-09-16 调查）

- 现状：LOWEST_COST 机台比较器主键 = `setupCostMin`（`estimateSetupCost`：需求声明 changeoverTimeMin → 机台 defaultSetupTimeMin → 0），平局键 plannedEnd/plannedStart/machineId（TaskSchedulingCalculator.java:1122-1131、838-856）；任务排序按 task.changeoverTimeMin（:1159-1165）。仅 LOWEST_COST 使用成本；其余策略与成本无关。
- 可派生数据：换模次数可从派工历史/运行时链（MachineLastAssignment、ResourceRuntimeContext）派生；跨班次次数可从日历班次结构与任务窗口派生；机台现有字段仅 tonnage/defaultSetupTimeMin，**无能耗字段**。
- 算法冻结约束：计算器其余语义（选择级联、时间计算、其他三策略）零回归；既有 270 离线 + 10 IT 全绿是基线。

## Key Decisions（用户已决，2026-09-16）

- **KD1**：MVP 因子 = 换型时间（现有）+ 换模次数惩罚（派工历史派生，零 DDL）+ 跨班次损耗（日历派生，零 DDL）；**能耗预留权重槽位默认 0**，待 master-data 有能耗字段后再接入。
- **KD2**：权重经 **planning 服务配置**（`@ConfigurationProperties`，application.yml 默认 + 环境变量覆盖），零 DDL；不建配置表、不硬编码。

## Requirements

- R1 — 综合成本：`compositeCost = w_setup×换型时间 + w_changeover×换模次数惩罚 + w_crossShift×跨班次损耗 + w_energy×能耗(预留恒0)`；各因子口径在 design.md 精确定义。
- R2 — 仅 LOWEST_COST 机台比较器主键切换为综合成本；平局键与其余三策略、任务级排序、选择级联、时间计算零变化。
- R3 — 权重配置化：yml 默认值 + env 覆盖；默认值保持业务可读基准并在配置注释中说明量纲。
- R4 — 集成套件补充 LOWEST_COST 综合成本场景（沿用 compose+REST 套件基建）。
- R5 — 既有测试零回归（若默认权重改变某既有 LOWEST_COST 用例结果，默认权重归零该因子并在记录中说明，改为用例级显式配置验证）。

## Acceptance Criteria

- [ ] AC1 — 综合成本按 design.md 口径计算，三因子单测覆盖（换型时间、换模次数派生、跨班次计数）与权重绑定测试全过。
- [ ] AC2 — LOWEST_COST 比较器按综合成本选择（不同因子主导的排序场景各有用例）；其余三策略比较器逐字节不变。
- [ ] AC3 — 能耗槽位存在且默认权重 0（无数据时不影响结果）；配置经 yml/env 可覆盖（绑定测试）。
- [ ] AC4 — 既有 270 离线用例与 10 IT 零回归（含既有 LOWEST_COST 用例结果不变）。
- [ ] AC5 — 集成套件新增 LOWEST_COST 综合成本场景并连跑全绿（同栈两轮）。
- [ ] AC6 — 配置项文档化（yml 注释 + README）；单体/根 pom/根 schema.sql/compose.dev.yml 零改动。

## Out of Scope

- 能耗真实建模（等 master-data 能耗字段，KD1）；成本落库/报表；其他策略的成本化；成本权重运行时热更（重启生效）。

## Task Notes

- 单任务（因子/配置/测试一条纵切，无需拆树）。
- 算法冻结红线：本任务唯一允许触碰的计算器区域是 LOWEST_COST 比较器主键与其因子派生私有方法；实现前先跑基线、实现后逐用例对照。
