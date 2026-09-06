# Implement Plan: route-scheduling-ext

## Checklist

1. [ ] Add `queue_policy` (and optional `eligible_resource_rule`) to `OperationTask` + schema
2. [ ] Populate fields in `buildBatchTasks`
3. [ ] Generalize changeover trigger via registry
4. [ ] Implement SAME_MOLD_FIRST in machine selection
5. [ ] Set capabilityCode from route op_code in registry rules
6. [ ] Update/add calculator tests
7. [ ] Run `mvn -q -pl product-pps test`

## Validation

```bash
mvn -q -pl product-pps test -Dtest=TaskSchedulingCalculatorTest,ChangeoverCalculatorTest
```

## Rollback

Drop new columns if migrated; revert calculator changes.
