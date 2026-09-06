# Design: PPS 工艺路线完整闭环

## Architecture Overview

```
┌─────────────────────┐     ┌──────────────────────────┐
│ ProductController   │────▶│ ProductRouteService      │
│ (optional route)    │     │  CRUD + activate         │
└─────────────────────┘     └────────────┬─────────────┘
                                         │ validate
                                         ▼
                            ┌──────────────────────────┐
                            │ RouteRuleRegistry        │
                            │  rule → resources        │
                            │  model → duration        │
                            └────────────┬─────────────┘
           ┌─────────────────────────────┼─────────────────────────────┐
           ▼                             ▼                             ▼
OperationTaskServiceImpl    OperationResourceRequirementBuilder   TaskSchedulingCalculator
 (std duration via registry)  (requirements via registry)         (queue_policy, changeover)
```

## Module Boundaries

| Module | Responsibility |
|--------|----------------|
| `product-common` | `RouteOperationConstants` 扩展；注册表接口/枚举 |
| `product-domain` | `ProductRoute`, `RouteOperation` 实体（已有） |
| `product-pps` | 路线 Service/Controller；注册表实现；排程扩展 |
| `product-demand` | 产品详情/创建可选嵌入路线；委托 pps 校验 |

## R1: Route Management API

**Endpoint prefix:** `/pps/product-route`

| Method | Path | Description |
|--------|------|-------------|
| GET | `/list?productId=` | 分页/按产品查路线 |
| GET | `/{routeId}` | 路线详情含工序 |
| POST | `/` | 创建路线+工序 |
| PUT | `/` | 更新路线+工序（全量替换工序） |
| PUT | `/{routeId}/activate` | 启用（停用同产品其他路线） |
| DELETE | `/{routeId}` | 删除（仅允许未启用或无比对中批次任务） |

**Product integration:**

- `Product.operations` 不作为持久字段；`selectProductByProductId` 联查启用路线。
- `insertProduct` / `updateProduct` 接受可选 `activeRoute` 对象，内部调用 `ProductRouteService.saveForProduct`。

**Activation invariant:** `UPDATE product_route SET is_active=0 WHERE product_id=?` then set target `is_active=1`.

## R2: Rule Registry

```java
interface RouteEligibleResourceRule {
    String code();
    List<TaskResourceRequirement> buildRequirements(OperationTask task, RouteOperation op);
    boolean triggersChangeover();  // true for SETUP-class rules
    boolean requiresMachine();
    boolean requiresWorkstation();
}

interface RouteStdTimeModel {
    String code();
    long calculateDurationMin(RouteDurationContext ctx);
}
```

**Bootstrap registrations (preserve P1 behavior):**

| Rule | Resources | Changeover |
|------|-----------|------------|
| `RULE_SETUP_MACHINE` | PERSON + MACHINE | yes |
| `RULE_INJECT_MACHINE` | PERSON + MACHINE + MOLD | no |
| `RULE_POST_WORKSTATION` | PERSON + WORKSTATION | no |

| Model | Duration |
|-------|----------|
| `TM_INJECT_A2` | InjectDurationCalculator |
| `TM_SETUP_BASE` | SETUP base × batch_qty |
| `TM_POST_UNIT` | POST unit × batch_qty |

**Validator:** `RouteOperationValidator.validate(List<RouteOperation>)` — non-empty, unique sequence, known rule/model codes.

**Refactor targets:**

- `OperationResourceRequirementBuilder` → delegates to registry
- `OperationTaskServiceImpl.resolveStdDurationMin` → delegates to registry

## R3: Scheduling Extensions

### queue_policy

Per-task policy sourced from originating `RouteOperation.queuePolicy` (stored on `OperationTask` transient field or resolved at schedule time via batch→product→route lookup).

| Policy | Machine selection bias |
|--------|------------------------|
| `FIFO` | Current greedy earliest-start (default) |
| `SAME_MOLD_FIRST` | Prefer machine where `machineLastAssignment.moldId == task.moldId`; tie-break by earliest start |
| `EDD` | Defer to global `SchedulingStrategy.DUE_DATE_PRIORITY` (no per-op override beyond inherit) |

Implementation: extend `chooseBestMachine` comparator via `machineChoiceComparator(strategy, queuePolicy, ...)`.

### Changeover generalization

Replace `"SETUP".equals(task.getOpCode())` with registry lookup:

```java
ruleRegistry.findRule(routeOp.getEligibleResourceRule()).triggersChangeover()
```

Fallback: if route metadata unavailable at schedule time, infer from task resource requirements (requires MACHINE + no MOLD + PERSON → setup-class).

### Custom op_code

- `capabilityCode` on resource requirements = `routeOperation.opCode` (not hardcoded SETUP/INJECT/POST).
- `resource_capability.op_code` must match for machine/person/workstation selection.
- Duration for unknown model → reject at route save (not at schedule time).

## Data Flow (End-to-End)

```
POST /demand/product { moldParams, activeRoute: { operations: [...] } }
  → validate mold + route rules
  → save product + product_route + route_operation

POST /pps/batch/release → generateTask
  → load active route
  → for each route_operation:
       task.opCode = route_operation.op_code
       stdDuration = registry.calculate(model, ctx)
       requirements = registry.build(rule, task)
  → save tasks + dependencies + requirements

POST /pps/assignment/scheduleAll
  → for each task:
       effectiveEarliest = max(deps, earliest_start)
       choose machine/workstation with queue_policy + changeover if setup-class
       verify resource_capability(op_code, product_id)
```

## Compatibility

- Existing DB rows with standard three operations continue to work unchanged.
- No breaking change to schedule job API.
- Routes without `queue_policy` default to `FIFO`.

## Rollout

1. Ship registry + validator (internal, no API).
2. Ship management API + product binding.
3. Refactor generator/scheduling to registry; add queue_policy + changeover generalization.
4. Seed demo route via API in integration test (not SQL).
