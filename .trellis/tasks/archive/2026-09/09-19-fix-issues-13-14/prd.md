# 修复 issue #13/#14：快照契约分块加载与 init 运行表重置

## Goal

- **#13 [P1][planning/demand]**：排程快照加载按契约 MAX_IDS=1000 分块，订单行>1000 不再使 scheduleAllAsync 确定性 FAILED；错误文案区分「暂时性不可用」与「响应形态异常」。
- **#14 [P3][dev-env]**：init SQL 对运行时/审计表补 TRUNCATE，使「可重复执行 = 重置回种子状态」对全部表成立（`*_data_version` 单调计数刻意保留，ADR-0005 既有设计）。

## Requirements

### R1（#13）SchedulingSnapshotLoader 分块加载

- `loadOrderLines` / `loadOrders` / `loadCalendars` / `loadProducts` 四个 ID 集合方法统一走新增的分块助手：入参去重（提供方拒绝重复 ID）→ 按 `DemandQueryRequests.MAX_IDS`（master-data 契约同值 1000）切片 → 逐片调用（保留 callWithAuthRetry 与 FeignException 映射）→ 按 key 合并；分块数 = ceil(n/1000)。
- 错误文案：FeignException（暂时性）保留「…请稍后重试」；响应形态异常（确定性）改为「需求服务响应异常，无法加载排程输入快照（请检查提供方日志与服务版本）」——去掉误导性的重试建议，保留既有测试断言的前缀「需求服务响应异常」。
- 删除死代码 `requireBoundedIds`（全仓零调用方，语义已被分块取代）。
- 类 javadoc「远程调用次数有界 ≤10 次」更新为「基础 ≤10 次 + 按 MAX_IDS 的分块数，仍与任务数无关」。
- `loadAvailableResources`（按类型全量，无 ID 列表）与 `ProductionBatchServiceImpl` 的契约调用（单批次量级）不受影响、不改。

### R2（#13）测试

- `SchedulingSnapshotLoaderTest` 新增：2501 个 orderLineIds → 3 次契约调用、每请求 ≤1000、合并结果 2501 条；重复 ID 去重后下发；1500 个 calendarIds → 2 次调用（master-data 侧同规则）。既有 9 用例（含 auth-retry 两条）必须原样通过（单分块路径不变）。

### R3（#14）init 脚本运行表重置

- `demand_schema.sql`：TRUNCATE planning_batch_state、event_outbox、consumed_event、dead_letter_audit、ops_audit；`demand_data_version` 保留（注释注明刻意）。
- `planning_schema.sql`：TRUNCATE event_outbox、consumed_event、dead_letter_audit、ops_audit（schedule_job 已是 DROP+CREATE 本就重置）。
- `execution_schema.sql`：TRUNCATE event_outbox、consumed_event、dead_letter_audit、ops_audit。
- `docs/微服务运维手册.md` 步骤 4 标注：运行时/审计表随重置清空、*_data_version 单调计数刻意保留。
- identity/master_data 脚本无运行时残留表（全部 DROP+CREATE / data_version 刻意保留），不改。

## Constraints

- 契约不变：MAX_IDS 校验留在提供方（纵深防御）；不改任何 API 形状；`RouteDurationContext` 类推不涉及。
- 快照版本化规则（capture/verify 不变式）不动。
- 遵循 spec：错误处理（ServiceException 口径）、测试约定（MockitoExtension）。

## Acceptance Criteria

- [ ] AC1：四个方法分块化，`SchedulingSnapshotLoaderTest` 新增 ≥3 用例 + 既有 9 用例全绿；planning `mvn test` 全绿。
- [ ] AC2：`requireBoundedIds` 移除，全仓 grep 无引用。
- [ ] AC3：三个 init 脚本 TRUNCATE 就位、data_version 保留注释；运维手册标注更新。
- [ ] AC4：trellis-check PASS；提交/推送/评论关闭 #13、#14。

## Notes

- #13 交叉证据：负载测试报告 §4 发现 2（L3 首轮 5000 行秒 FAILED、demand 日志「批量查询ID数超限: 5000 > 1000」、改拆批建模后 15000 任务 SUCCESS——瓶颈仅在快照获取一跳）。
- #13 的「契约级 1001 订单行集成测试」与游标式快照接口列为后续项（issue 评论说明）。
