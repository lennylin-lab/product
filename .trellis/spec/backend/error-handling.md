# Error Handling

> How errors are handled in this project.

---

## Overview

Business logic throws `ServiceException`. A single `@RestControllerAdvice` in `product-framework` catches all exceptions and returns `AjaxResult` to the client. Controllers do not wrap service calls in try-catch for business errors.

Reference: `product-common/.../ServiceException.java`, `product-framework/.../GlobalExceptionHandler.java`

---

## Business Exceptions

Use `ServiceException` for expected business rule violations:

```java
throw new ServiceException("订单不存在");
throw new ServiceException("任务已存在派工记录: " + String.join(",", existingTaskIds));
throw new ServiceException("message", customCode);  // optional integer code
```

Reference files:
- `product-pps/.../TaskAssignmentPersistenceService.java`
- `product-demand/.../CustomerOrderServiceImpl.java`
- `product-pps/.../TaskSchedulingCalculator.java`

`ServiceException` is a `RuntimeException` with optional `code` and `detailMessage` fields. Throw it directly from service methods; do not create module-specific exception hierarchies.

---

## Global Exception Handler

`GlobalExceptionHandler` maps exception types to `AjaxResult`:

| Exception | HTTP semantics | Client message |
|-----------|---------------|----------------|
| `ServiceException` | 200 with error code in body | `e.getMessage()` (optional custom `code`) |
| `AccessDeniedException` | 403 | "没有权限，请联系管理员授权" |
| `SQLException` / `DataAccessException` / MyBatis exceptions | 200 with error in body | Friendly DB message (duplicate key, FK, null, etc.) |
| `HttpRequestMethodNotSupportedException` | 200 with error in body | Method not supported message |
| `BindException` / `MethodArgumentNotValidException` | 200 with error in body | First validation error message |
| `MissingPathVariableException` | 200 with error in body | Missing path variable name |
| `MethodArgumentTypeMismatchException` | 200 with error in body | Type mismatch detail |
| `RuntimeException` / `Exception` | 200 with error in body | Generic or message-based |

All handler methods log with `log.error(...)` including the request URI.

Reference: `product-framework/.../GlobalExceptionHandler.java`

---

## Error Propagation Pattern

**Service layer:** validate and throw `ServiceException` when business rules fail.

```java
if (order == null) {
    throw new ServiceException("订单不存在");
}
```

**Controller layer:** call service directly, return `AjaxResult` on success. No try-catch needed.

```java
@PostMapping
public AjaxResult add(@RequestBody TaskAssignment entity) {
    return toAjax(taskAssignmentService.save(entity));
}
```

**Do not** catch `ServiceException` in controllers — let `GlobalExceptionHandler` handle it.

Reference: `TaskAssignmentController`, `CustomerOrderServiceImpl`

---

## API Error Response Format

All errors return `AjaxResult`:

```json
{ "code": 500, "msg": "订单不存在" }
```

Permission errors use `HttpStatus.FORBIDDEN` (403) as the code:

```json
{ "code": 403, "msg": "没有权限，请联系管理员授权" }
```

Success responses use `code: 200`. Reference: `product-common/.../AjaxResult.java`, `HttpStatus.java`.

---

## Controller-Level Validation

For request body validation, use `@Validated` on the parameter (seen in system controllers):

```java
@PostMapping
public AjaxResult add(@Validated @RequestBody SysMenu menu) { ... }
```

Validation failures are caught by `GlobalExceptionHandler` as `MethodArgumentNotValidException`.

Reference: `product-system/.../SysMenuController.java`

For business-rule validation that depends on DB state (uniqueness, status transitions), check in the service and throw `ServiceException`.

---

## Common Mistakes

- **Creating custom exception classes per module** — use `ServiceException` only.
- **Returning error strings from controllers** — throw `ServiceException` or return `error("message")` for simple controller-level checks.
- **Swallowing exceptions in services** — log and rethrow, or throw `ServiceException` with a clear message.
- **Try-catch in controllers for business errors** — unnecessary; the global handler covers it.
