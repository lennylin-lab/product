# PPS排程 P0 改进：任务依赖、A2 时长、资源明细清理

## Goal

修复审查中识别的三个 P0 问题，使排程结果在工序顺序、注塑时长和资源占用清理上更正确、更可靠。

## Requirements

### R1 排程读取任务依赖

- 全量排程时加载 `task_dependency`，对后置任务约束 `plannedStart >= max(前置任务 planned_end)`。
- 前置任务结束时间来源：数据库已有派工 + 当前排程批次内已计算结果。
- 与现有 `earliest_start` 取 max，不替代生成阶段的静态约束。

### R2 INJECT 工序使用 A2 产能公式

- 任务生成时，对 `INJECT` 工序按 `product_mold_param` 计算 `std_duration_min`。
- 公式口径与 `docs/关键配置流程.md` 一致。
- 产品必须配置有效 `product_mold_param`；缺失或无效时拒绝生成 INJECT 任务（无回退）。
- `SETUP` / `POST_QC_PUTAWAY` 保持现有算法不变。

### R3 撤销/重生成时显式清理资源明细

- `revokeSchedule` 与 `retryGenerateTask` 在删除 `task_assignment` 前/同时显式删除 `task_assignment_resource`。
- 不完全依赖数据库级联，避免环境差异导致孤儿明细。

## Out of Scope

- `product_route` 驱动任务生成
- `changeover_rule` 接入
- 跨班次任务分段
- `POST_QC_PUTAWAY` 资源需求模型调整

## Acceptance Criteria

- [x] 排程时后置任务的 `planned_start` 不早于其前置任务 `planned_end`（同批次与跨批次均成立）
- [x] INJECT 任务在有 `product_mold_param` 时按 A2 公式写入 `std_duration_min`
- [x] INJECT 任务在无模具参数时被拒绝并给出明确错误
- [x] `revokeSchedule` / `retryGenerateTask` 显式删除对应 `task_assignment_resource`
- [x] 新增/更新单元测试覆盖上述行为
- [x] `product-pps` 相关测试通过

## Notes

- 多模具参数时选用同一产品下第一条可用记录（与当前未选模阶段一致）。
- 依赖约束在 Calculator 层实现，QueryService 负责批量加载。
