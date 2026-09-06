# Implement Plan: route-rule-registry

## Checklist

1. [ ] Add `RouteDurationContext` record
2. [ ] Add rule interface + 3 implementations
3. [ ] Add model interface + 3 implementations
4. [ ] Add `RouteRuleRegistry` with registration
5. [ ] Add `RouteOperationValidator`
6. [ ] Refactor `OperationResourceRequirementBuilder` to use registry
7. [ ] Refactor `OperationTaskServiceImpl.resolveStdDurationMin` to use registry
8. [ ] Add unit tests
9. [ ] Run `mvn -q -pl product-pps test`

## Validation

```bash
mvn -q -pl product-pps test -Dtest=RouteRuleRegistryTest,RouteOperationValidatorTest,OperationResourceRequirementBuilderTest
```

## Rollback

Revert registry package; restore switch statements in builder/service.
