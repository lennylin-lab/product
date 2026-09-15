# 夹具协同资源接入排程 — Design

## Architecture and Boundaries

- Master-data owns fixture master records, fixture compatibility records, and any fixture capabilities. Planning consumes them only through product-master-data-api; it must not directly query master_data_db.
- Planning owns task requirements, scheduling decisions, runtime occupancy, task_assignment, and task_assignment_resource writes.
- The existing generic resource model remains the shared scheduling abstraction. FIXTURE should be added as a resource type rather than as a special task-only field.
- task_assignment remains machine-centric for legacy compatibility; fixture occupancy belongs in task_assignment_resource together with mold/person/workstation rows.

## Data Flow

1. Master-data stores fixture resources as resource.resource_type = FIXTURE plus fixture extension rows and compatibility rows.
2. resources/batch returns fixture resources in the same versioned response envelope as other resources.
3. SchedulingSnapshotLoader.loadAvailableResources(...) maps fixture DTOs into planning Resource models and filters non-available rows consistently with existing resources.
4. Route/resource requirement construction creates TaskResourceRequirement(resourceType=FIXTURE) rows when a route rule requires a fixture.
5. TaskSchedulingCalculator includes fixture selection after the primary machine/workstation path has enough context to evaluate compatibility, then takes max(machine/mold/person/workstation/fixture availability, earliestStart, assignmentStart).
6. The resolved fixture requirement is persisted by the existing TaskAssignmentPersistenceService.appendRequirementAssignments(...) generic resource path.

## Compatibility Model

- MVP compatibility grain is fixture ↔ mold, implemented as an explicit allow-list keyed by fixture_id and mold_id.
- Compatibility should be explicit for fixture-aware operations: a task requiring fixture must not silently use every fixture when compatibility data is absent for the selected or required mold.
- Existing resource_capability may continue to express operation/product capability; fixture compatibility expresses physical fixture/mold fit and must not duplicate product/operation capability semantics.
- Product-specific or operation-specific fixture compatibility dimensions are deferred unless future business rules prove that the same fixture/mold pair can be valid for one product or operation but invalid for another.

## Scheduling Semantics

- Fixture is a collaborative resource, not a primary route branch. Machine tasks can require MACHINE + MOLD + PERSON + FIXTURE; workstation tasks may require WORKSTATION + PERSON + FIXTURE only if the route rule explicitly requires it.
- Fixture availability contributes to plannedStart; the selected fixture is occupied for the same planned window as the task.
- Fixture sequence is independent under ResourceRuntimeContext using resourceType=FIXTURE.
- Existing strategies (EARLIEST_START, EARLIEST_FINISH, DUE_DATE_PRIORITY, LOWEST_COST) keep their current comparator semantics unless fixture compatibility removes a candidate resource combination.

## Compatibility and Migration Notes

- Additive schema/API changes only: existing resources without fixture data must deserialize and schedule unchanged.
- Keep root schema.sql in sync only if the project convention for current init scripts requires root aggregate schema updates; do not add executable runtime behavior to old monolith modules.
- Update comments/dictionary converter text where resource type enumerations are user-visible.
- Preserve master-data version bump behavior on fixture and compatibility writes so planning snapshot drift detection remains valid.

## Rollback

- Fixture code paths should be behind additive resource requirements: if no route produces FIXTURE requirements, existing scheduling path should behave exactly as before.
- DB rollback is table/column additive rollback in master_data_schema.sql; planning tables likely need no structural change beyond comments/indexes unless a fixture-specific mirror table is introduced.

## Risks

- Fixture ↔ mold compatibility may be too coarse if later business rules require product/operation-specific fixture constraints; this is an explicit deferred extension, not part of MVP.
- Generic resource persistence already supports new resource type strings, but calculator ResourceChoice currently has explicit fields for known collaborative resource types and will need careful extension to avoid duplicate or missing rows.
- Route rule defaults must avoid changing existing SETUP, INJECT, and POST_QC_PUTAWAY behavior unless fixture-aware rules are explicitly configured.
