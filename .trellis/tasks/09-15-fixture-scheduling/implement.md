# 夹具协同资源接入排程 — Implementation Plan

## Execution Order

1. 09-15-fixture-modeling — complete first.
   - Add FIXTURE constants in master-data and planning resource constant classes.
   - Add master-data fixture schema/entity/mapper/service/controller or minimal CRUD path matching existing resource extension conventions.
   - Extend ResourceDTO and InternalMasterDataController.toResourceDTO(...) to carry fixture extension data.
   - Extend planning resource model and SchedulingSnapshotLoader mapping.
   - Add tests for fixture resource loading and non-fixture backward compatibility.
2. 09-15-fixture-compatibility — start only after modeling tests pass.
   - Add fixture ↔ mold compatibility persistence shape.
   - Add DTO/domain mapping for compatibility rows.
   - Add route/resource rule support that emits FIXTURE requirements only for fixture-aware configured rules.
   - Add tests for compatible, incompatible, and absent compatibility cases.
3. 09-15-fixture-assignment-occupancy — start only after fixture requirements can be produced.
   - Extend calculator resource choice with fixture ID and fixture sequence.
   - Select fixture from eligible available resources and include fixture nextAvailableTime in effective start.
   - Resolve selected fixture IDs into TaskResourceRequirement copies and update runtime context under FIXTURE.
   - Verify TaskAssignmentPersistenceService writes fixture rows through the generic requirement path; patch only if current generic behavior is insufficient.
   - Add timing/sequence/persistence tests.

## Validation Commands

- mvn -pl product-services/product-master-data-api,product-services/product-master-data,product-services/product-planning -am test
- If module-scoped tests reveal cross-module regressions: mvn -pl product-services -am test or repo-standard full Java validation command discovered during trellis-before-dev.

## Review Gates

- Do not run task.py start until this updated planning summary is approved in a subsequent user message.
- Before implementation, load trellis-before-dev and the backend spec docs listed in implement.jsonl.
- After implementation, run trellis-check/quality verification and update spec if a reusable compatibility or generic-resource pattern is learned.

## Rollback Points

- After modeling: schema/API changes are additive; rollback by reverting fixture constants, DTO/model fields, and fixture schema/entity files.
- After compatibility: rollback by removing compatibility table/DTO/rule additions while keeping pure FIXTURE resource modeling if already validated.
- After occupancy: rollback calculator changes independently; existing non-fixture routes should still work if no FIXTURE requirements are emitted.
