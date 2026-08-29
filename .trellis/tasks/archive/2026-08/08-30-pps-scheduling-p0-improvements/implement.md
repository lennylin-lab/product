# Implement Plan

## Checklist

1. [x] Add `InjectDurationCalculator` + unit test
2. [x] Update `OperationTaskServiceImpl.generateTask/retryGenerateTask` for A2 INJECT duration
3. [x] Add dependency load methods to `TaskSchedulingQueryService`
4. [x] Wire dependency context in `TaskSchedulingCoordinator`
5. [x] Apply dependency constraint in `TaskSchedulingCalculator`
6. [x] Add `TaskAssignmentResourceMapper.deleteByTaskIds` + XML
7. [x] Update `revokeSchedule` / `retryGenerateTask` cleanup
8. [x] Add/update tests; run `mvn -pl product-pps test`

## Validation

```bash
mvn -q -pl product-pps test -Dtest=InjectDurationCalculatorTest,TaskSchedulingCalculatorTest,TaskSchedulingQueryServiceTest,TaskAssignmentPersistenceServiceTest
```

## Rollback

Revert commits touching Calculator/QueryService/OperationTaskServiceImpl; no DB changes.
