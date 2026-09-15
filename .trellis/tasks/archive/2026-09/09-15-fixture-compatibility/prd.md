# 夹具兼容规则接入

## Goal

定义夹具兼容规则和路由需求生成方式，使 fixture-aware 工序能够生成可排程的 FIXTURE 资源需求。

## Requirements

- Add fixture ↔ mold compatibility persistence and DTO/domain mapping.
- Keep compatibility explicit for fixture-aware operations: no compatible fixture means task is not schedulable.
- Keep resource_capability focused on op/product capability; use fixture ↔ mold compatibility for physical fit rules.
- Extend route/resource rules so only explicitly configured fixture-aware operations emit mandatory FIXTURE requirements.
- Preserve current SETUP, INJECT, and POST_QC_PUTAWAY behavior unless a new or updated rule explicitly requests fixture.

## Acceptance Criteria

- [ ] Fixture ↔ mold compatibility rows are queryable through master-data batch snapshot contracts.
- [ ] Planning maps compatibility rows into an in-memory model suitable for calculator filtering.
- [ ] Requirement builder emits FIXTURE rows only for fixture-aware route rules.
- [ ] Tests cover compatible, incompatible, and absent compatibility data.
- [ ] Existing route rule tests stay green.

## Dependencies

- Depends on 09-15-fixture-modeling.
- Parent compatibility-grain decision is resolved: MVP uses fixture ↔ mold.

## Out of Scope

- Runtime occupancy and plannedStart calculation.
- Cost model changes beyond metadata needed for compatibility.
