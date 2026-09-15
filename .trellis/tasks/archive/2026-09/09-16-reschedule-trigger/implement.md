# 09-16-reschedule-trigger — Implementation Plan

> 阅读顺序：implement.jsonl → 本任务 design.md → 父任务 design（数据流 3b/4、KD2）→
> `PlanningEventConsumerService.consumeResourceStatusChanged`（child-1 交付的插入点）、
> `ScheduleJobServiceImpl.scheduleAllAsync`、`ScheduleJobTimeoutService` 实际代码。

## Checklist（按序执行）

- [x] 1. 触发集合常量（DOWN/AVAILABLE）+ `RescheduleTriggerService`
  （置标 + 立即提交 + 异常分级：互斥吞掉/其他保留标记）。
- [x] 2. `consumeResourceStatusChanged` 插入触发调用（回写成功后、record(APPLIED) 前；
  仅触发集合），EXCEPTION/task.status.changed 路径零改动。
- [x] 3. sweeper 排空：`ScheduleJobTimeoutService` 既有 @Scheduled 节拍内追加
  「标记存在且无运行任务 → 提交 + 清标记」步骤。
- [x] 4. 测试（design.md Tests Required 五组全项）+ 既有回归（child-1 消费测试、
  互斥/超时/sweeper 测试）全绿。
- [x] 5. 实机端到端（需用户许可窗口，用自己的脚本与端口，transcript 存任务 scratch/）：
  起栈 → DOWN 事件 → 回写 + 自动重排 + DOWN 资源未被选中 → 恢复事件 → 再次重排；
  顺带补 child-1 check 移交的实机探测（200 错误体回写失败通道）。
- [x] 6. 文档：`docs/TODO.md` P1 行收尾；README 事件基线表触发语义；父任务 implement.md
  状态更新。

## Validation Commands

- `cd product-services && mvn -B -ntp clean test`（基准 248 全绿 + 新增用例，记录精确数字）
- 根仓库 `mvn -B -ntp -DskipTests compile`（单体回滚点 28/28）

## Review Gates

- 触发动作不得使消费失败进入重试/DLX（标记即持久化）；不得绕过 scheduleAllAsync 互斥。
- EXCEPTION/task.status.changed 路径零改动（回归证明）。
- 单体/根 pom/根 schema.sql/compose.dev.yml 零改动；新代码构造器注入；测试离线可跑。
- 实机部分：只杀自己记录的 PID/容器；transcript 存本任务 scratch/。
- 勾选后交 trellis-check 校验；通过后父任务收尾（跨子任务端到端评审 + 归档）。

## Rollback Points

- 触发链整体可独立回滚（消费退化为「只回写不触发」，回写与事件链不受影响）；
  Redis 标记 key 带 TTL，无清理脚本需求。

## 执行记录（2026-09-16，trellis-implement）

### Clean-run 证据（本次 `product-services && mvn -B -ntp clean test`，BUILD SUCCESS，1:06 min）

- 总计 **270 tests，Failures 0，Errors 0，Skipped 0**（基准 248 → +22 新增）。
- 分模块（本轮 surefire 汇总，日志 `scratch/child2_clean_test.log`）：
  cloud-common 21 / cloud-security 11 / cloud-messaging 8 / master-data-api 0 / demand-api 0 /
  planning-api 0 / gateway 17 / identity 31 / master-data 20 / demand-service 27 /
  planning **112**（+22）/ execution 23。
- 新增用例（全离线 Mockito 单测）：
  `RescheduleTriggerServiceTest` 11 条（置标→提交→清标顺序、人工入口同款默认参数、
  互斥拒绝吞掉保留标记、意外异常吞掉、置标失败仍尽力提交、排空五分支、触发集合/key 常量契约）；
  `PlanningEventConsumerRescheduleTriggerTest` 7 条（DOWN 顺序契约 InOrder 回写→触发→APPLIED、
  AVAILABLE 触发、MAINTENANCE/OFFSHIFT/BUSY 只回写不触发、触发失败不影响 ack、回写失败绝不触发、
  重复事件跳过触发、EXCEPTION task 事件不触碰触发链）；`ScheduleJobTimeoutServiceDrainTest` 4 条
  （@Scheduled 节拍同拍排空、手动清扫入口不排空、排空异常不影响清扫、锁不可得静默跳过仍排空）。
- child-1 消费测试（`PlanningEventConsumerServiceTest` 12 条）与既有互斥/超时清扫测试
  （`PlanningRedisLockTest` 3、`PlanningApplicationTest` 3）**零改动全绿**（git 状态可见仅新增
  测试文件，无既有测试文件修改）。
- 根仓库 `mvn -B -ntp -DskipTests compile`：**28/28 SUCCESS**（`scratch/child2_root_compile.log`）。

### 实现要点（与 design 对应）

1. `RescheduleTriggerService`（新，构造器注入 StringRedisTemplate + IScheduleJobService）：
   `requestReschedule()` = SET `planning:reschedule:pending`（value=置标时间戳，TTL 30min）→
   `scheduleAllAsync(new TaskAssignmentDTO())`（全空字段=人工入口空 body 同语义）→ 成功清标；
   互斥 ServiceException 吞掉标记保留、其他异常 error 日志保留标记、置标失败仅记录仍尽力提交，
   本方法不向上抛。`drainPendingIfIdle()` = 标记存在且无 PENDING/RUNNING schedule_job →
   尝试补跑 → 成功清标返回 true；有任务/互斥/异常均保留标记返回 false。
2. 清标用**比较删除**（Lua：value==置标时间戳才 DEL）：并发事件重复置标时慢提交不误清
   新标记（防丢重排的竞态收口；design 已规定 value=置标时间戳，此处落实其用途）。
3. 消费侧插入点：`writeBackResourceStatus(envelope)` 成功返回后、`record(APPLIED)` 前
   `requestRescheduleIfTriggered`（toStatus ∈ {DOWN, AVAILABLE} 裁决 + 兜底 catch）；
   消费者保留 3 参构造器（委托 null 触发器）供 child-1 既有测试零改动编译运行，
   Spring 接线用 @Autowired 4 参构造器。EXCEPTION/task.status.changed 路径零改动
   （`exceptionTaskEventShouldNeverTouchRescheduleTrigger` 钉住）。
4. `ScheduleJobTimeoutService` 由字段注入转为构造器注入（新增 RescheduleTriggerService 依赖）；
   `sweepTimeoutJobs()` 在既有超时清扫后同拍调用排空；手动清扫入口 `sweepTimeoutJobsOnce()`
   语义不变（不排空，测试钉住）。

### 实机端到端（栈 pids 见 `scratch/reschedule-trigger/pids.txt`；新栈保持运行）

- 重建 fresh jars 重启 6 服务（旧 pids 1915638-1915643 为本会话所有，已停止；
  新 pids identity 2040244 / master-data 2040245 / demand-service 2040246 / planning 2040247 /
  execution 2040248 / gateway 2040249），全部 health 200。
- 端到端 21/21 PASS（`scratch/e2e/e2e_reschedule_transcript.txt`，脚本
  `scratch/reschedule-trigger/e2e_reschedule_probe.py`）：播种 2 机台 + 6 READY 任务 →
  人工基线排程 SUCCESS（200/201 均承接）→ DOWN 事件（execution /internal 直连登记）→
  master_data_db resource 200=DOWN + 版本 bump（121→122）→ **无人调排程接口出现新
  schedule_job SUCCESS（6 任务）** → machine_id=200 派工行数=0（全部由 201 承接）→
  consumed_event APPLIED +1 → AVAILABLE 恢复事件 → 再次自动重排 SUCCESS → 200 回归派工 →
  版本再次 bump（122→123）。
- 触发链日志证据（`scratch/e2e/trigger_log_evidence.txt`）：每个事件按序
  「回写 master-data 成功 snapshotVersion=…」→「资源状态事件触发全量重排: jobId=…（pending
  标记已清除）」→「资源状态事件进入重排触发集: toStatus=…」。
- 非触发集合实测（`scratch/e2e/nontrigger_probe.txt` 6/6 PASS）：MAINTENANCE 回写生效
  （bump + consumed_event +1）但无新排程任务；随后 AVAILABLE 恢复照常自动重排。
- child-1 check 移交的回写失败通道（`scratch/e2e/writeback_failure_channel_evidence.txt`）：
  toStatus=BROKEN（非法）→ master-data 错误契约（200+错误体）→ planning
  「资源状态回写响应非法（提供方拒绝或契约异常）」抛错不 ack → 3 次重试耗尽
  （RejectAndDontRequeueRecoverer）→ DLQ 死信审计入库（dead_letter_audit 3→4、
  consumed_event 无新增、版本与资源状态不变）。

### 偏差（已记录）

1. **live 库 schema 补齐**：本次起栈发现 master_data_db 缺 fixture 阶段两张表（`fixture`、
   `fixture_mold_compatibility`——夹具任务的 DDL 从未应用到该 live 库，导致 resources/batch
   契约 500「Table 'master_data_db.fixture' doesn't exist」→ 快照空 → 排程「没有可用机台」）。
   已按 init 脚本逐字 DDL 以 CREATE TABLE IF NOT EXISTS 补建（仅 live 库，代码/根 schema.sql
   零改动）。
2. **live 库脏数据清理**：删除孤儿测试数据 MACHINE resource 2099649811721846786（早前会话
   探测遗留：resource 行在、machine 扩展行 tonnage=NULL 且无模具兼容行，被机台选择选中后
   模具阶段失败「任务79000022没有可用资源」）。属环境数据清理，非代码改动。
3. 首轮 probe 断言缺陷修正（probe 脚本侧）：consumed_event 死信核对基线应在恢复事件之后取；
   machine_id=NULL 行是工位任务的合法形态（-1 归类展示），DOWN 排除断言改为
   「machine_id=200 行数=0」。最终 transcript 为修正后全绿一轮。
4. 其余零偏差：单体/根 pom/根 schema.sql/compose.dev.yml 零改动；错误契约零改动；
   EventTypes/execution/master-data 主代码零改动（child-1 交付面冻结）。
