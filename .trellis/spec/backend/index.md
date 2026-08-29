# Backend Development Guidelines

> Best practices for backend development in this project.

---

## Overview

This directory contains guidelines derived from the actual codebase. They document how this project works today — not aspirational patterns — so AI agents and developers produce code that matches existing conventions.

**Stack:** Java 17, Spring Boot 3.5, MyBatis-Plus 3.5, Spring Security (JWT), SLF4J/Logback.

---

## Guidelines Index

| Guide | Description | Status |
|-------|-------------|--------|
| [Directory Structure](./directory-structure.md) | Module layout, API routes, controller conventions | Filled |
| [Database Guidelines](./database-guidelines.md) | MyBatis-Plus entities, mappers, queries | Filled |
| [Error Handling](./error-handling.md) | ServiceException, GlobalExceptionHandler, AjaxResult | Filled |
| [Quality Guidelines](./quality-guidelines.md) | Auth, testing, required/forbidden patterns | Filled |
| [Logging Guidelines](./logging-guidelines.md) | SLF4J, Logback, MDC, log levels | Filled |

---

## Quick Reference

| Topic | Primary reference files |
|-------|------------------------|
| API routes | `TaskAssignmentController`, `SysMenuController`, `BaseController` |
| Authentication | `SecurityConfig`, `PermissionService`, `SecurityUtils` |
| Logging | `logback-spring.xml`, `TraceMdcFilter`, `GlobalExceptionHandler` |
| Testing | `TaskSchedulingCalculatorTest`, `TaskAssignmentPersistenceServiceTest` |
| Database | `TaskAssignment` entity, `TaskAssignmentMapper`, `schema.sql` |

---

**Language**: All documentation in this directory is written in **English**.
