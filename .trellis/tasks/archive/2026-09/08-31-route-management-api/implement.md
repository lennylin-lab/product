# Implement Plan: route-management-api

## Checklist

1. [ ] Verify module dependency (demand → pps or shared API)
2. [ ] Add `IProductRouteService` + `ProductRouteServiceImpl`
3. [ ] Add `ProductRouteController`
4. [ ] Wire `RouteOperationValidator` on save/activate
5. [ ] Extend `Product` entity with transient `activeRoute`
6. [ ] Update `ProductServiceImpl` get/insert/update
7. [ ] Add service/controller tests
8. [ ] Run `mvn -q -pl product-pps,product-demand test`

## Validation

```bash
mvn -q -pl product-pps,product-demand test -Dtest=ProductRouteServiceTest
```

## Rollback

Remove controller/service; revert Product entity changes.
