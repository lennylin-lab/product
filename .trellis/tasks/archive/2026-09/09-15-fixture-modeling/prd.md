# 夹具资源主数据建模

## Goal

新增夹具资源类型、主数据模型和排程快照契约，使 planning 可以像加载机台、模具、人员、工位一样加载可用夹具资源。

## Requirements

- Add FIXTURE as an additive resource type in product-services master-data and planning constants, comments, and user-visible converter text.
- Store fixture master data in master_data_db using the existing resource-plus-extension convention.
- Expose fixture extension data through product-master-data-api resource batch DTOs without breaking existing consumers.
- Map fixture DTOs into planning in-memory Resource models and keep existing AVAILABLE filtering and snapshot-version behavior.
- Preserve old monolith modules as read-only reference; do not implement fixture behavior there.

## Acceptance Criteria

- [ ] FIXTURE appears in relevant product-services resource constants and init schema comments.
- [ ] master-data has a fixture extension model/persistence path consistent with Machine/Mold extension patterns.
- [ ] resources/batch can return fixture resources and remains backward compatible for current resource responses.
- [ ] SchedulingSnapshotLoader maps fixture resources and non-fixture snapshot tests stay green.
- [ ] Modeling tests cover available and non-available fixture resources.

## Dependencies

- Parent task: 09-15-fixture-scheduling.
- Must complete before 09-15-fixture-compatibility and 09-15-fixture-assignment-occupancy.

## Out of Scope

- Fixture compatibility selection logic.
- Calculator allocation/occupancy logic.
