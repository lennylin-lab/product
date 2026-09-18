# 修复 issue #12：generateTask 时长模型去批量线性占位

## Goal

修复 [P1][planning] #12：SETUP=60×qty、POST=120×qty 的占位时长模型使 batchQty≥7 的批次任务必然超过 720min 班次窗口，`scheduleAllAsync` 整作业 FAILED「没有可用资源」。目标态：工序标准时长不再随批量线性放大（INJECT 产能公式除外），任意现实批量可排。

## Requirements

### R1 三处 ×batchQty 乘法全部移除

1. `SetupBaseTimeModel`：返回固定基准 `STD_DURATION_MIM.get(0)`（60min/次换型）。依据：`RouteDurationContext` 无机台/日历字段，generateTask 时机台未分配，`machine.default_setup_time_min` 与 changeover_rule 均不可得；机台间实际换型差值本就由排程期 `ChangeoverCalculator` 独立叠加，SETUP 工序时长不应再乘批量。
2. `PostUnitTimeModel`：返回固定基准 `STD_DURATION_MIM.get(2)`（120min/批质检入库，占位）。类名与 `TM_POST_UNIT` 模型码保持不变（模型码已落库，改名破坏数据）；真实单位工时数据接入后再启用单位语义，届时须配班次上限校验/分批（issue 建议的后续项）。
3. `RouteRuleRegistry.calculateDurationMin` 兜底分支（未知模型码路径）：`baseDuration * context.batchQty()` → `baseDuration`。

### R2 测试

- `RouteRuleRegistryTest.calculateDurationMinShouldUseSetupBaseModel` 现断言 qty=2→120（旧行为），改为批次无关断言（qty=1 与 qty=100 均 60）。
- 新增：POST 模型 qty=100→120；注册表兜底（未知模型码）qty=100→基准值而非 ×qty；「qty=100 时三工序时长全部 ≤720min 班次窗口」的 issue 级回归断言。

### R3 决策记录

- `product-services/README.md` 决议表补 #12 行：与单体（product-pps 同款模型，冻结不动）的显式行为差异——任务标准时长不再随批量放大。

## Constraints

- 范围 = product-services/product-planning；单体冻结。
- 不改 `RouteDurationContext` 结构、`TM_*` 模型码、任何 API 形状；INJECT 产能公式不动。
- issue 另两条建议列为后续项不在本次范围（issue 评论中显式说明）：①时长>班次窗口的 generateTask 提前校验（需跨域查日历，现模型已有界且排程侧 fail-safe）；②generateTask→scheduleAllAsync 全链路跨批量集成测试（本次以单元级语义钉死 + 建议后续 IT）。

## Acceptance Criteria

- [ ] AC1：三处乘法移除，全仓 grep `batchQty` 线性放大仅剩 INJECT 计算器。
- [ ] AC2：RouteRuleRegistryTest 更新+新增用例全绿，含 qty=100 三工序 ≤720min 回归断言；planning 模块 `mvn test` 全绿。
- [ ] AC3：README 决议表 #12 行落地。
- [ ] AC4：trellis-check PASS；提交/推送/评论关闭 #12。

## Notes

- 根因链已核实：adjustForShiftEnd（TaskSchedulingCalculator:1378）对超窗任务整单顺延→720min 窗口下时长≥720 的任务永不放置→候选资源清零→L119 ServiceException；阈值 math：POST 120×6=720 恰好可排、×7=840 必不可排，与 issue 一致。
- 负载测试报告（.trellis/tasks/09-19-scale-scheduling-loadtest/report.md §4 发现 1）为交叉证据，其 L1-L3 档位被迫用 qty=5 的根因即本 issue。
