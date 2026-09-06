# Design: 工艺路线管理 API

## Service Layer

`ProductRouteService` / `ProductRouteServiceImpl` in `product-pps`:

```java
ProductRoute getByRouteId(String routeId);
ProductRoute getActiveByProductId(Long productId);
List<ProductRoute> listByProductId(Long productId);
String createRoute(ProductRoute route);      // returns routeId
void updateRoute(ProductRoute route);
void activateRoute(String routeId);
void deleteRoute(String routeId);
void saveActiveRouteForProduct(Long productId, ProductRoute route);
```

## Controller

`ProductRouteController` @RequestMapping("/pps/product-route")

## Product Module Integration

`ProductServiceImpl`:

```java
private final ProductRouteService productRouteService; // inject from pps module

selectProductByProductId:
  product.setActiveRoute(productRouteService.getActiveByProductId(productId));

insertProduct / updateProduct:
  if (product.getActiveRoute() != null) {
    productRouteService.saveActiveRouteForProduct(productId, activeRoute);
  }
```

**Module dependency:** ensure `product-demand` depends on `product-pps` service interface, or extract `IProductRouteService` API to shared module. Prefer `product-pps` exposes interface consumed by `product-demand` (check existing module graph).

## Delete Guard

```sql
-- block delete if enabled route has active tasks
SELECT 1 FROM operation_task t
JOIN production_batch b ON t.batch_id = b.batch_id
JOIN order_line ol ON b.order_line_id = ol.order_line_id
WHERE ol.product_id = ? AND t.status IN ('READY','SCHEDULED','IN_PROCESS')
LIMIT 1
```

## Transaction Boundaries

- create/update/activate: `@Transactional`
- activate + deactivate siblings: single transaction
