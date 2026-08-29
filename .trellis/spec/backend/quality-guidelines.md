# Quality Guidelines

> Code quality standards for backend development.

---

## Overview

These guidelines reflect **actual patterns in the codebase**, including known inconsistencies. Follow the dominant pattern in the module you are editing.

---

## Authentication and Authorization

### Global authentication (JWT)

All requests except explicitly permitted URLs require a valid JWT. Configuration is centralized in `product-auth`:

- Stateless sessions (`SessionCreationPolicy.STATELESS`)
- JWT validated by `JwtAuthenticationTokenFilter`
- Anonymous URLs: hardcoded (`/login`, `/register`, `/captchaImage`) + URLs collected from `@Anonymous` annotation via `PermitAllUrlProperties`
- Filter order: `TraceMdcFilter` → `CorsFilter` → `JwtAuthenticationTokenFilter`

Reference: `product-auth/.../SecurityConfig.java`, `product-auth/.../JwtAuthenticationTokenFilter.java`

### Method-level authorization

When permissions are enforced, use Spring `@PreAuthorize` with the `ss` bean (`PermissionService`):

```java
@PreAuthorize("@ss.hasPermi('system:menu:list')")
@GetMapping("/list")
public AjaxResult list(SysMenu menu) { ... }
```

Permission string format: `{module}:{resource}:{action}` (e.g. `system:menu:add`, `tool:gen:list`).

Reference: `product-system/.../SysMenuController.java`, `product-auth/.../PermissionService.java`

Available SpEL helpers on `@Service("ss")`:
- `@ss.hasPermi('permission')`
- `@ss.hasAnyPermi('perm1,perm2')`
- `@ss.hasRole('role')`
- `@ss.hasAnyRoles('role1,role2')`

### Current state (document reality)

| Area | Authorization pattern |
|------|----------------------|
| `product-system` (menu, dict, gen) | `@PreAuthorize` on most mutating/list endpoints |
| `product-pps`, `product-demand`, `product-master`, `product-execute` | **No** `@PreAuthorize` — authenticated users only |
| `SysUserController` | `@PreAuthorize` annotations are **commented out** |
| `@RequiresPermissions` | **Not used anywhere** in this project |

When adding endpoints to **system admin** modules, follow `SysMenuController` and add `@PreAuthorize`. When adding endpoints to **business domain** modules (`pps`, `demand`, `master`, `execute`), match existing controllers: no method-level permissions unless explicitly requested.

Do **not** introduce `@RequiresPermissions` — this project uses Spring Security `@PreAuthorize`, not Shiro-style annotations.

### Getting current user in code

Use `SecurityUtils` static methods or `BaseController` helpers:

```java
LoginUser user = SecurityUtils.getLoginUser();
Long userId = SecurityUtils.getUserId();
String username = SecurityUtils.getUsername();
boolean admin = SecurityUtils.isAdmin(userId);
```

Reference: `product-core/.../SecurityUtils.java`, `BaseController.getLoginUser()`

---

## Testing Requirements

### Framework and style

New tests should use **JUnit 5** (`org.junit.jupiter.api.Test`) with **Mockito** for dependency mocking. This is the established pattern in `product-pps` and `product-execute`.

```java
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TaskAssignmentPersistenceServiceTest {
    @Test
    void persistBatchResultShouldWriteMainAssignmentAndAssignedResources() {
        TaskAssignmentMapper mapper = mock(TaskAssignmentMapper.class);
        when(mapper.selectExistingTaskIds(anyList())).thenReturn(List.of());
        // ...
        verify(taskAssignmentResourceMapper).batchInsert(captor.capture());
    }
}
```

Reference:
- `product-pps/.../TaskSchedulingCalculatorTest.java` — pure unit tests, no Spring context
- `product-pps/.../TaskAssignmentPersistenceServiceTest.java` — Mockito + `ReflectionTestUtils.setField`
- `product-execute/.../TaskEventServiceImplTest.java` — `Recording*` test subclasses

### Test conventions observed

1. **Pure unit tests** — no `@SpringBootTest`, no `@WebMvcTest`, no `@MockBean`
2. **Mock dependencies** with `mock()` + `ReflectionTestUtils.setField` for `@Autowired` fields
3. **Testable subclasses** — extend the service under test to override specific methods when mocking is insufficient
4. **Descriptive test names** — `orderTasksShouldPrioritizeEarliestFinishWhenRequested`
5. **Chinese comments on tests** explaining the scenario (optional but present in pps tests)
6. **ServiceException assertions** — catch and assert message in demand tests

Reference: `product-demand/.../CustomerOrderServiceImplTest.java`

### What is NOT tested (yet)

- No controller/integration tests exist
- No `@SpringBootTest` beyond empty `contextLoads()` in server/core
- `product-demand` still has legacy JUnit 3 `TestCase` stubs — do not add new tests in that style

When adding tests for new service logic, place them in `{module}/src/test/java/` mirroring the source package. Follow the JUnit 5 + Mockito pattern from `product-pps`.

### Running tests

```bash
# Single module
mvn test -pl product-pps

# Single test class
mvn test -pl product-pps -Dtest=TaskSchedulingCalculatorTest
```

---

## Required Patterns

| Pattern | Where |
|---------|-------|
| Extend `BaseController` for business REST controllers | All domain controllers except auth |
| Return `AjaxResult` / `TableDataInfo` | All controller endpoints |
| Throw `ServiceException` for business errors | Service layer |
| Extend `ServiceImpl<Mapper, Entity>` | Service implementations |
| Extend `BaseMapper<Entity>` | Mapper interfaces |
| Extend `BaseEntity` | Domain entities |
| Use `@Mapper` on mapper interfaces | All mappers |
| Use `@Slf4j` for logging | New service/config/handler classes |

---

## Forbidden Patterns

| Pattern | Why |
|---------|-----|
| `@RequiresPermissions` | Not used; project uses `@PreAuthorize("@ss.hasPermi(...)")` |
| Shiro or custom auth filters | Project uses Spring Security + JWT |
| Constructor injection in controllers | Existing code uses `@Autowired` fields |
| Per-module exception hierarchies | Use `ServiceException` only |
| JUnit 3 `TestCase` for new tests | Legacy in demand module only |
| `@SpringBootTest` for unit tests | No precedent; use pure unit tests with mocks |
| Entities outside `product-domain` | Breaks shared model contract |

---

## Code Review Checklist

When reviewing or generating backend code:

1. Controller extends `BaseController`, uses standard CRUD path naming
2. Service throws `ServiceException` for business rule violations (not controller)
3. Entity in `product-domain`, extends `BaseEntity`, has `@TableName`
4. Mapper extends `BaseMapper`, custom SQL in XML under `resources/mapper/`
5. If in system module, mutating endpoints have `@PreAuthorize`
6. Logging uses `@Slf4j`; errors logged with exception attached
7. New service logic has JUnit 5 unit test with Mockito (if test-worthy logic)

---

## Known Inconsistencies (do not "fix" unless asked)

- Business domain controllers lack `@PreAuthorize` while system controllers have it
- `SysUserController` has all permission annotations commented out
- `@Slf4j` vs `LoggerFactory` mixed in utility classes
- `MyMetaObjectHandler` and `BaseController.getDataTable()` log at INFO on every call
- JUnit 3 stubs coexist with JUnit 5 tests in `product-demand`

When editing a file, match the surrounding module's style rather than normalizing the entire codebase.
