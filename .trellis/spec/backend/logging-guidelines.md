# Logging Guidelines

> How logging is done in this project.

---

## Overview

Logging uses **SLF4J** with **Logback**, configured in `product-server/src/main/resources/logback-spring.xml`. Request-scoped context (traceId, userId, etc.) is injected via MDC in `TraceMdcFilter`.

---

## Logger Declaration

Two patterns exist in the codebase. **Prefer Lombok `@Slf4j`** for new code in services, controllers, config, and handlers:

```java
@Slf4j
public class TaskSchedulingCoordinator { ... }
```

Legacy utility classes use `LoggerFactory.getLogger`:

```java
private static final Logger log = LoggerFactory.getLogger(ImageUtils.class);
```

When editing existing utils in `product-common`, keep the existing style. For all new classes in business and framework modules, use `@Slf4j`.

Reference:
- `@Slf4j`: `product-framework/.../GlobalExceptionHandler.java`, `product-pps/.../TaskSchedulingCoordinator.java`
- `LoggerFactory`: `product-common/.../utils/file/ImageUtils.java`, `product-common/.../utils/Threads.java`

---

## Log Levels

| Level | When to use | Examples in codebase |
|-------|-------------|---------------------|
| `error` | Exceptions, failed operations | `GlobalExceptionHandler` (all handlers), `OperationTaskServiceImpl` parallel query failure |
| `warn` | Recoverable anomalies, scheduling edge cases | `TaskSchedulingCalculator` (no mold/person available), `TaskSchedulingCoordinator` (schedule failure) |
| `info` | Significant business events, config startup | `TaskSchedulingCoordinator` (schedule completion stats), `SecurityConfig` (bean creation), `SysLoginService` (login flow) |
| `debug` | Detailed diagnostic (disabled in prod by default) | `OperationTaskServiceImpl` batch quantity logging |

Default levels in `logback-spring.xml`: root = INFO, `com.product` = INFO.

---

## Structured Logging and MDC

### Console format

Includes timestamp, level, PID, thread, **traceId** (from MDC), logger name, message:

```
%d{yyyy-MM-dd HH:mm:ss.SSS} %5p ${PID} --- [%t] [%X{traceId}] %logger : %m
```

### File output (JSON)

Three rolling appenders:
- `app.json.log` — general application logs
- `error.json.log` — ERROR level only
- `audit.json.log` — audit channel (`sys-user` logger)

JSON fields include: `@timestamp`, `service.name`, `service.env`, `level`, `logger`, `message`, `traceId`, `requestId`, `userId`, `username`, `clientIp`, `httpMethod`, `requestUri`.

Reference: `product-server/src/main/resources/logback-spring.xml`

### MDC population

`TraceMdcFilter` (registered before JWT filter in `SecurityConfig`) sets MDC fields for each request so downstream logs include trace and user context.

Reference: `product-auth/.../TraceMdcFilter.java`, `product-auth/.../SecurityConfig.java`

---

## What to Log

**Do log:**
- Exception details in handlers (`log.error("请求地址'{}',发生系统异常.", requestURI, e)`)
- Scheduling/ batch operation outcomes with key metrics (task count, duration)
- Authentication events (login failure, logout) via `SecurityUtils.recordAuthFailure` / `recordLogoutEvent`
- Configuration bean initialization at INFO during startup

**Existing patterns:**

```java
// Scheduling completion with metrics
log.info("scheduleAll completed: tasks={}, batches={}, machines={}, totalCostMs={}", ...);

// Recoverable business condition
log.warn("任务 {} 没有可用模具(机台 {})", task.getTaskId(), machineChoice.machineId);

// Exception with context
log.error("并行查询批次与任务信息失败，batchIds={}", batchIds, e);
```

Reference: `TaskSchedulingCoordinator.java`, `TaskSchedulingCalculator.java`, `OperationTaskServiceImpl.java`

---

## What NOT to Log

- **Passwords, tokens, or captcha values** in production paths (note: `SysLoginService` currently logs captcha at INFO — do not extend this pattern)
- **Full request/response bodies** containing PII
- **Per-row CRUD operations** at INFO (avoid adding logs inside tight loops)
- **Routine MyBatis fill operations** at INFO on every insert/update (existing `MyMetaObjectHandler` does this — do not replicate)

---

## Audit Logging

Security-sensitive user actions use the dedicated `sys-user` logger, which writes only to `audit.json.log`. Reference: `SecurityUtils.recordLogoutEvent`, `ShutdownManager`.

When adding audit-worthy events (permission changes, password resets), route through `SecurityUtils` or the `sys-user` logger rather than the default application logger.

---

## Common Mistakes

- **Adding INFO logs inside hot paths** (pagination totals, field auto-fill) — creates noise; use DEBUG if needed.
- **Logging sensitive data** — captcha codes, passwords, full JWT tokens.
- **Mixing `@Slf4j` and `LoggerFactory` in the same module** without reason — pick `@Slf4j` for new code.
- **Logging and swallowing exceptions** — always log with the exception as the last argument: `log.error("msg", e)`.
