# 夹具分配与占用计算

## Goal

扩展排程级联选择、运行时状态和持久化明细，使需要夹具的任务在选择、开始时间、序号和占用记录上都真实消耗 FIXTURE 资源。

## Requirements

- Select an eligible available fixture for tasks with mandatory FIXTURE requirements.
- Include fixture next-available time in effective plannedStart and plannedEnd recomputation.
- Update ResourceRuntimeContext for FIXTURE with independent nextAvailableTime and sequence.
- Resolve selected fixture IDs back into TaskResourceRequirement copies for persistence.
- Persist fixture rows in task_assignment_resource using the generic requirement-assignment path where possible.
- Keep all non-fixture task paths behaviorally unchanged.

## Acceptance Criteria

- [ ] Scheduler rejects fixture-required tasks when no compatible available fixture exists.
- [ ] Scheduler delays fixture-required tasks until the selected fixture is available.
- [ ] Runtime context tracks FIXTURE availability and sequence independently from MACHINE/MOLD/PERSON/WORKSTATION.
- [ ] task_assignment_resource contains a FIXTURE row with correct task, requirement, time window, and sequence.
- [ ] Existing scheduling and persistence tests remain green.

## Dependencies

- Depends on 09-15-fixture-modeling and 09-15-fixture-compatibility.

## Out of Scope

- New front-end views for fixture gantt visualization.
- Exception-event driven rescheduling.
