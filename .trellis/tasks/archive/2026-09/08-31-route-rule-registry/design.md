# Design: 工序规则与工时模型注册表

## New Classes (product-pps)

```
com.product.pps.route
  RouteRuleRegistry          @Component, holds rule+model maps
  RouteOperationValidator    @Component
  RouteDurationContext       record
  rule/
    RouteEligibleResourceRule (interface)
    SetupMachineRule
    InjectMachineRule
    PostWorkstationRule
  model/
    RouteStdTimeModel (interface)
    InjectA2TimeModel
    SetupBaseTimeModel
    PostUnitTimeModel
```

## Registry Wiring

Spring `@PostConstruct` or constructor injection registers all `@Component` rule/model beans into immutable maps keyed by `code()`.

## Refactor

| Before | After |
|--------|-------|
| `OperationResourceRequirementBuilder` switch | `registry.requireRule(rule).buildRequirements(task, op)` |
| `OperationTaskServiceImpl.resolveStdDurationMin` switch | `registry.requireModel(model).calculateDurationMin(ctx)` |

## Testing

- Table-driven tests for each rule/model
- Validator: happy path + unknown code + duplicate sequence + empty list
