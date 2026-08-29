# Directory Structure

> How backend code is organized in this project.

---

## Overview

This is a **Java 17 / Spring Boot 3.5** multi-module Maven project. Business logic is split by domain module; shared infrastructure lives in `product-common`, `product-core`, `product-framework`, and `product-domain`. The runnable application is assembled in `product-server`.

Reference: root `pom.xml`, `product-server/pom.xml`.

---

## Module Layout

```
product/
├── product-server/          # Application entry, aggregates all modules
├── product-common/          # Shared utils, constants, annotations, BaseEntity
├── product-domain/          # Shared entities, DTOs, VOs (no controllers)
├── product-core/            # BaseController, SecurityUtils, ExcelUtil, LoginUser
├── product-framework/       # GlobalExceptionHandler, MyBatis config, interceptors
├── product-auth/            # Spring Security, JWT, login/captcha controllers
├── product-system/          # System admin (user, menu, dict)
├── product-system-api/      # ISys*Service interfaces only
├── product-demand/          # Customer, order, product domain
├── product-pps/             # Production planning & scheduling
├── product-master/          # Master data (calendar, machine/resource)
├── product-execute/         # Shop-floor task events
├── product-become/          # Code generator (Velocity templates)
└── product-cache/           # Redis cache utilities
```

**Dependency flow:** `product-server` → business modules → `product-core` / `product-framework` → `product-common` + `product-domain`.

---

## Layer Placement Within a Business Module

Each domain module (e.g. `product-pps`) follows this layout:

```
product-pps/src/main/java/com/product/pps/
├── controller/              # REST endpoints
├── service/
│   ├── I*Service.java       # Service interface
│   └── impl/*ServiceImpl.java
├── mapper/                  # MyBatis-Plus mapper interfaces
├── dto/                     # Module-local DTOs (optional)
└── enums/                   # Module-local enums (optional)

product-pps/src/main/resources/
└── mapper/*Mapper.xml       # Custom SQL for complex queries
```

**Reference modules:**
- `product-pps/` — well-structured CRUD + scheduling services
- `product-demand/` — standard domain CRUD
- `product-system/` — admin controllers with permission annotations

---

## API Route Conventions

### Controller annotations

Business controllers use `@RestController` + `@RequestMapping("/{domain}/{resource}")` and extend `BaseController`.

| Module prefix | Example path | Reference |
|---------------|--------------|-----------|
| `/pps/*` | `/pps/assignment`, `/pps/task`, `/pps/batch` | `TaskAssignmentController` |
| `/demand/*` | `/demand/order`, `/demand/customer` | `CustomerOrderController` |
| `/master/*` | `/master/calendar`, `/master/resource/machine` | `CalendarController`, `MachineController` |
| `/execute/*` | `/execute/event` | `TaskEventController` |
| `/system/*` | `/system/menu`, `/system/user` | `SysMenuController` |
| `/common` | shared file upload/download | `CommonController` |

Auth endpoints (`/login`, `/register`, `/captchaImage`, `/logout`) live in `product-auth` and **do not** extend `BaseController`.

Reference files:
- `product-pps/src/main/java/com/product/pps/controller/TaskAssignmentController.java`
- `product-system/src/main/java/com/product/system/controller/SysMenuController.java`
- `product-auth/src/main/java/com/product/auth/controller/SysLoginController.java`

### Standard CRUD endpoint shape

Code-generated and hand-written business controllers share this pattern:

| Method | Path | Return type | Purpose |
|--------|------|-------------|---------|
| `GET` | `/list` | `TableDataInfo` | Paginated list |
| `POST` | `/export` | `void` | Excel export |
| `POST` | `/importTemplate` | `void` | Download import template |
| `POST` | `/importData` | `AjaxResult` | Import from Excel |
| `GET` | `/{id}` | `AjaxResult` | Single record |
| `POST` | `/` | `AjaxResult` | Create |
| `PUT` | `/` | `AjaxResult` | Update |
| `DELETE` | `/{ids}` | `AjaxResult` | Delete (comma-separated IDs) |

Pagination: call `PageUtils.buildPage()` to get a MyBatis-Plus `Page<T>`, pass it to the service, then wrap with `getDataTable(page)` from `BaseController`.

Mutations: use `toAjax(int rows)` or `toAjax(boolean result)` to convert DB row counts into `AjaxResult`.

Reference: `product-core/src/main/java/com/product/core/controller/BaseController.java`

### Response types

- **Single result / mutation:** `AjaxResult` via `success()`, `error()`, `warn()`, or `toAjax()`
- **Paginated list:** `TableDataInfo` (fields: `code`, `msg`, `rows`, `total`)

Both types live in `product-common`.

### Dependency injection

Controllers use field `@Autowired` injection. This is the established pattern across all modules; do not introduce constructor injection in new controllers unless refactoring an entire module.

---

## Naming Conventions

| Artifact | Pattern | Example |
|----------|---------|---------|
| Controller | `{Entity}Controller` | `TaskAssignmentController` |
| Service interface | `I{Entity}Service` | `ITaskAssignmentService` |
| Service impl | `{Entity}ServiceImpl` | `TaskAssignmentServiceImpl` |
| Mapper | `{Entity}Mapper` | `TaskAssignmentMapper` |
| Entity | `{Entity}` in `product-domain` | `TaskAssignment` |
| Table name | snake_case | `task_assignment` |
| URL segment | camelCase or lowercase | `/demand/orderLine` |

---

## Where NOT to Put Code

- **Entities/DTOs/VOs** → `product-domain`, not inside business modules (except module-local DTOs like `product-pps/dto/`)
- **Security config** → `product-auth`, not in business modules
- **Global exception handling** → `product-framework`, not per-module
- **Mapper XML** → `{module}/src/main/resources/mapper/`, not alongside Java sources

---

## Examples

Well-organized modules to copy when adding features:

1. **Standard CRUD controller:** `product-pps/.../TaskAssignmentController.java`
2. **Controller with permissions:** `product-system/.../SysMenuController.java`
3. **Service + mapper:** `product-pps/.../TaskAssignmentServiceImpl.java` + `TaskAssignmentMapper.java`
4. **Shared entity:** `product-domain/.../entity/TaskAssignment.java`
