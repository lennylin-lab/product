# Design: PPS 排程 P0 改进

## Boundaries

| Layer | Change |
|-------|--------|
| `TaskSchedulingQueryService` | 批量加载依赖图、前置任务 planned_end |
| `TaskSchedulingCalculator` | 计算时合并依赖约束到 effective earliest start |
| `TaskSchedulingCoordinator` | 编排时传入依赖上下文 |
| `InjectDurationCalculator` (new) | A2 公式纯函数组件 |
| `OperationTaskServiceImpl` | 生成 INJECT 时长、显式清理资源明细 |
| `TaskAssignmentResourceMapper` + XML | `deleteByTaskIds` |
| Tests | Calculator / QueryService / InjectDuration / Persistence |

## R1: Task Dependency Flow

```
loadReadyTaskList()
    → loadPostToPredecessorsMap(taskIds)
    → loadPlannedEndByTaskIds(allPreTaskIds)
    → calculateBatchAssignments(..., depMap, predecessorEnds)
         for each task:
           effectiveEarliest = max(earliestStart, depConstraint, assignmentStart)
           chooseResources(..., effectiveEarliest)
           predecessorEnds.put(taskId, plannedEnd)
```

- `postToPredecessors`: `post_task_id → List<pre_task_id>`
- `predecessorEndTimes`: mutable map, seeded from DB then updated in-memory per batch

## R2: A2 Duration

```
batch → order_line → product_id
product_id → product_mold_param (first row)
A2(batch_qty, param) → std_duration_min  (INJECT only; param required)
```

Component: `com.product.pps.service.impl.InjectDurationCalculator`

Missing or invalid `product_mold_param` → `ServiceException` / batch-level error (no fallback).

## R3: Resource Detail Cleanup

```sql
DELETE FROM task_assignment_resource WHERE task_id IN (...)
```

Called in `revokeSchedule` and `retryGenerateTask` before removing `task_assignment`.

## Compatibility

- No schema migration required.
- Existing API contracts unchanged.
- Greedy scheduler behavior preserved except dependency-aware earliest start.
