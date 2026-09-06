# Design: 排程引擎工艺路线扩展

## Schema Addition (minimal)

```sql
ALTER TABLE operation_task ADD COLUMN queue_policy varchar(32) NULL COMMENT '工序排队策略';
```

Mirror in `schema.sql` + `deploy/mysql/init/010-schema.sql`.

## Task Generation

`OperationTaskServiceImpl.buildBatchTasks`:

```java
operationTask.setQueuePolicy(routeOperation.getQueuePolicy());
operationTask.setEligibleResourceRule(routeOperation.getEligibleResourceRule()); // optional transient for schedule
```

## Changeover Trigger

`TaskSchedulingCalculator.chooseBestMachine`:

```java
boolean needsChangeover = routeRuleRegistry
    .findRule(task.getEligibleResourceRule())
    .map(RouteEligibleResourceRule::triggersChangeover)
    .orElse(inferSetupClassFromRequirements(task));
```

## SAME_MOLD_FIRST

In machine comparator:

1. If policy is SAME_MOLD_FIRST and task has mold requirement:
   - Score machines where `lastAssignment.moldId == chosenMoldId` higher
2. Tie-break: earliest planned start (existing logic)

## Capability Code

`OperationResourceRequirementBuilder` / registry rules set `capabilityCode = routeOperation.getOpCode()`.

## Tests

- Extend `TaskSchedulingCalculatorTest` with SAME_MOLD_FIRST scenario
- Add changeover test with custom op_code `TOOL_CHANGE` + `RULE_SETUP_MACHINE`
- Add custom post op `PACK` + `RULE_POST_WORKSTATION`
