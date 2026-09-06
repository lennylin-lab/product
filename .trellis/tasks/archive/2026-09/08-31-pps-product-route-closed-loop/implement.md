# Implement Plan: PPS 工艺路线完整闭环

## Execution Order

```
08-31-route-rule-registry
        ↓
08-31-route-management-api
        ↓
08-31-route-scheduling-ext
        ↓
Parent integration review + full test
```

## Parent Checklist

1. [ ] Child `route-rule-registry` archived
2. [ ] Child `route-management-api` archived
3. [ ] Child `route-scheduling-ext` archived
4. [ ] End-to-end: API 创建路线 → 释放批次 → 生成任务 → 全量排程
5. [ ] Cross-child acceptance criteria in parent `prd.md` all checked
6. [ ] Spec update (`.trellis/spec/backend/`) if new API conventions added

## Validation (full stack)

```bash
mvn -q -pl product-pps,product-demand test
```

Optional focused:

```bash
mvn -q -pl product-pps test -Dtest=RouteRuleRegistryTest,ProductRouteServiceTest,TaskSchedulingCalculatorTest
```

## Rollback

- Revert per-child commits independently.
- No destructive schema migration; API additions only.

## Review Gates

- After child 1: registry unit tests green; unknown rule/model rejected in validator test.
- After child 2: API integration test creates route and activates.
- After child 3: scheduling tests cover SAME_MOLD_FIRST + setup-class changeover.
