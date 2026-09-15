# 夹具协同资源接入排程

## Goal

将夹具从当前“无模型”状态推进到“可参与排程计算”状态，使 product-services 微服务体系能够在排程输入快照中加载夹具，按兼容规则生成夹具需求，并在分配时把夹具可用时间纳入任务 plannedStart/plannedEnd 计算与 task_assignment_resource 占用明细。

## Background

- docs/TODO.md 将夹具协同资源列为 P1 缺口，并明确后续功能一律在 product-services 落地，旧单体模块仅作对照，不再加功能。证据：docs/TODO.md:56, docs/TODO.md:64-65。
- 当前排程已经支持机台、模具、人员、工位选择与占用，核心入口在 product-services/product-planning/src/main/java/com/product/planning/service/impl/TaskSchedulingCalculator.java。证据：docs/TODO.md:46。
- 当前资源类型常量仅包含 MACHINE/MOLD/PERSON/WORKSTATION，尚无 FIXTURE。证据：product-services/product-planning/src/main/java/com/product/planning/common/constant/ResourceConstants.java:9-14；仓库全文搜索未发现 fixture/FIXTURE/夹具 的现役实现。
- 主数据资源表已有通用 resource 主表和 resource_capability 能力矩阵，模具通过扩展表 mold 与兼容表 machine_mold_compatibility 挂载到资源聚合。证据：product-services/product-master-data/src/main/resources/db/init/master_data_schema.sql:36-85。
- 主数据批量契约 resources/batch 已承载资源聚合、机台/模具扩展、机模兼容和能力矩阵，planning 侧通过 SchedulingSnapshotLoader 映射为排程内存模型。证据：product-services/product-master-data-api/src/main/java/com/product/masterdata/api/MasterDataBatchQueryApi.java:48-59、product-services/product-planning/src/main/java/com/product/planning/service/impl/SchedulingSnapshotLoader.java:325-352。
- 排程运行时上下文已经按 (resourceType, resourceId) 维护最早可用时间与序号，持久化资源占用明细也从 resourceRequirementList 泛化写入资源类型。证据：product-services/product-planning/src/main/java/com/product/planning/service/impl/TaskSchedulingCalculator.java:155-173、product-services/product-planning/src/main/java/com/product/planning/service/impl/TaskAssignmentPersistenceService.java:124-155。

## Requirements

- R1 — Scope only product-services: all new fixture behavior must land in product-services services/APIs/tests/docs; root legacy monolith modules may be read for comparison only and must not receive new fixture functionality.
- R2 — Model fixture as a first-class collaborative resource: introduce FIXTURE resource type and the minimum master-data extension required to describe fixture identity/status/calendar/capabilities without duplicating existing resource semantics.
- R3 — Preserve resource snapshot contract: expose fixture data through the existing master-data batch resource aggregate shape or an explicitly versioned compatible extension; planning must continue loading resources in bounded batch calls and fail closed on unavailable master-data responses.
- R4 — Add fixture-mold compatibility rules: define fixture eligibility through an explicit fixture ↔ mold allow-list, and persist enough compatibility data for planning to choose only fixtures compatible with the selected or required mold.
- R5 — Generate fixture requirements from route rules: task resource requirements must be able to include mandatory FIXTURE rows for relevant operations, while operations without fixture requirements must retain current behavior.
- R6 — Include fixture in scheduling allocation: when a task requires a fixture, the calculator must select an eligible available fixture, include fixture availability in effective start calculation, update fixture runtime state, and keep existing machine/mold/person/workstation behavior stable.
- R7 — Persist fixture occupancy: selected fixture requirements must be resolved to resource IDs and written to task_assignment_resource with correct resource_type, requirement_id, planned_start, planned_end, and per-resource sequence.
- R8 — Test the full path: unit/contract tests must cover fixture snapshot loading, compatibility filtering, requirement generation, allocation timing, runtime sequencing, and persistence; existing tests for non-fixture scheduling must remain green.

## Task Map

- 09-15-fixture-modeling — first child; owns FIXTURE resource modeling, master-data schema/API/DTO/domain mapping, and snapshot loading support.
- 09-15-fixture-compatibility — second child; depends on modeling; owns compatibility data semantics and route/resource requirement generation.
- 09-15-fixture-assignment-occupancy — third child; depends on modeling and compatibility; owns calculator selection, runtime occupancy, and persistence verification.

## Key Decisions

- KD1 — MVP fixture compatibility grain is fixture ↔ mold. Product/operation-specific fixture compatibility is out of scope for this task; operation/product capability can continue to use resource_capability if needed.

## Acceptance Criteria

- [ ] AC1 — Master-data and planning constants/schema/API/docs recognize FIXTURE without removing or changing existing MACHINE/MOLD/PERSON/WORKSTATION semantics.
- [ ] AC2 — resources/batch can return available fixture resources and fixture ↔ mold compatibility rows with enough fields for planning to make compatibility and capability decisions under the existing snapshot version drift guard.
- [ ] AC3 — Route/task requirement construction can produce mandatory fixture requirements for configured fixture-aware operations and leaves fixture-free operations unchanged.
- [ ] AC4 — Scheduling rejects tasks requiring a fixture when no compatible available fixture exists, with the same fail-fast ServiceException style used for unavailable machine/mold/person resources.
- [ ] AC5 — Scheduling delays a task until the selected fixture is available when fixture availability is later than machine/mold/person/workstation availability.
- [ ] AC6 — Successful assignments write a task_assignment_resource row for the fixture and maintain independent sequence_on_resource for fixture occupancy.
- [ ] AC7 — Existing non-fixture scheduling tests continue passing, and new fixture tests pass at the relevant module level.

## Out of Scope

- Old monolith feature development outside product-services.
- Exception event modeling, reporting-failure modeling, resource status event state-machine refresh, and rescheduling triggers; those remain the next P1 item in docs/TODO.md.
- Enhanced cost model inputs such as energy consumption, fixture-change loss, or cross-shift loss; those remain P2.
- Product-specific or operation-specific fixture compatibility dimensions beyond fixture ↔ mold.
- Front-end pages and business operation manuals.
