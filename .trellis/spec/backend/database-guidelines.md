# Database Guidelines

> Database patterns and conventions for this project.

---

## Overview

This project uses **MyBatis-Plus 3.5** on top of MySQL. There is no Flyway/Liquibase migration runner in the codebase; schema changes are tracked in the root `schema.sql` file. Entities live in `product-domain`; mapper interfaces and XML live in each business module.

Reference: `schema.sql`, `product-framework/.../MybatisConfig.java`, `product-server/.../ProductServerApplication.java` (`@MapperScan("com.product.**.mapper")`).

---

## Entity Conventions

All persistent entities:

1. Live in `product-domain/src/main/java/com/product/domain/entity/`
2. Extend `BaseEntity` (provides `createTime`, `updateTime`, `searchValue`, `params`)
3. Use Lombok `@Data` + `@EqualsAndHashCode(callSuper = true)`
4. Map to tables with `@TableName("snake_case_table")`
5. Use `@TableId(value = "column_name", type = IdType.AUTO)` for auto-increment PKs
6. Use `@TableField(value = "column_name")` when Java field name differs from column
7. Annotate exportable columns with `@Excel(name = "...")`

Reference: `product-domain/.../entity/TaskAssignment.java`, `product-common/.../entity/BaseEntity.java`

```java
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("task_assignment")
public class TaskAssignment extends BaseEntity {
    @TableId(value = "assignment_id", type = IdType.AUTO)
    private Long assignmentId;

    @TableField(value = "task_id")
    private String taskId;
}
```

### Automatic timestamp fill

`createTime` and `updateTime` are filled by `MyMetaObjectHandler` via `@TableField(fill = FieldFill.INSERT)` / `INSERT_UPDATE` on `BaseEntity`. Do not set these manually in service code unless overriding for a specific reason.

Reference: `product-framework/.../MyMetaObjectHandler.java`

---

## Table and Column Naming

Observed in `schema.sql`:

| Rule | Example |
|------|---------|
| Table names | snake_case, singular or compound | `task_assignment`, `customer_order` |
| Primary keys | `{entity}_id` or descriptive name | `assignment_id`, `order_id` |
| Timestamps | `create_time`, `update_time` | on most tables |
| Foreign keys | `{referenced_table}_id` | `customer_id` |
| Status fields | `varchar(20)` with comment describing enum values | `status` on `customer_order` |

---

## Mapper Conventions

Mapper interfaces:

1. Live in `{module}/mapper/`
2. Annotated with `@Mapper`
3. Extend `BaseMapper<Entity>` for standard CRUD
4. Declare custom queries with `@Param` annotations; implement in XML

Reference: `product-pps/.../mapper/TaskAssignmentMapper.java`

```java
@Mapper
public interface TaskAssignmentMapper extends BaseMapper<TaskAssignment> {
    Page<TaskAssignmentVO> selectTaskAssignmentPage(
        Page<TaskAssignmentVO> page,
        @Param("taskAssignment") TaskAssignment taskAssignment);
}
```

### XML placement

Custom SQL goes in `{module}/src/main/resources/mapper/{Entity}Mapper.xml`. Simple CRUD does not need XML — MyBatis-Plus generates it from `BaseMapper`.

Reference: `product-pps/src/main/resources/mapper/TaskAssignmentMapper.xml`

---

## Service Layer Patterns

Services extend MyBatis-Plus `ServiceImpl<Mapper, Entity>` and implement an `I*Service` interface:

```java
public class TaskAssignmentServiceImpl
    extends ServiceImpl<TaskAssignmentMapper, TaskAssignment>
    implements ITaskAssignmentService { ... }
```

Reference: `product-pps/.../TaskAssignmentServiceImpl.java`, `product-demand/.../CustomerOrderServiceImpl.java`

### Pagination

Use MyBatis-Plus `Page<T>` with `PageUtils.buildPage()` at the controller boundary. Custom page queries accept `Page<VO>` as the first parameter in the mapper method.

### Batch operations

Prefer batch mapper methods (e.g. `batchInsert`, `batchMarkScheduled`) over looping single-row inserts in service code. Reference: `TaskAssignmentPersistenceService`, `OperationTaskMapper.batchMarkScheduled`.

### Pre-insert validation

Check for conflicts in service code before writing, then rely on DB constraints as a second line of defense:

```java
List<String> existing = taskAssignmentMapper.selectExistingTaskIds(taskIds);
if (!existing.isEmpty()) {
    throw new ServiceException("任务已存在派工记录: " + String.join(",", existing));
}
```

Reference: `product-pps/.../TaskAssignmentPersistenceService.java`

---

## Migrations

There is no automated migration tool in this project. Schema changes are applied by updating `schema.sql` at the repository root. When adding tables or columns, follow existing naming in that file and add comments describing enum values and foreign keys.

---

## Common Mistakes

- **Putting entities in business modules** — all shared entities belong in `product-domain`.
- **Missing `@TableField`** when the Java property name uses camelCase but the DB column is snake_case.
- **Forgetting `callSuper = true`** on `@EqualsAndHashCode` when extending `BaseEntity`.
- **Looping inserts** instead of using batch mapper methods for multi-row writes.
- **Manual `createTime`/`updateTime` assignment** — rely on `MyMetaObjectHandler` unless there is a documented exception.
