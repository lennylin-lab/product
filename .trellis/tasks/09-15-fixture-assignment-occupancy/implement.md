# 09-15-fixture-assignment-occupancy — Implementation Plan

> 前置：09-15-fixture-modeling（aa8e211）、09-15-fixture-compatibility（ef892d8）已提交。
> 阅读顺序：implement.jsonl → 本任务 design.md → 父任务 design（Data Flow 5–6、
> Scheduling Semantics、Risks）→ `TaskSchedulingCalculator.java` 全文（算法语义冻结，
> 只加不改）→ microservices-platform.md / quality-guidelines.md。

## Checklist（按序执行）

- [x] 1. `ResourceChoice` 增加 `fixtureId`/`fixtureSequence`（可空）；既有字段与构造点不破坏。
- [x] 2. 机台分支夹具选择阶段（design.md 五步：需求过滤 → 候选 → 显式兼容过滤 → 最早可用 →
  有效时间纳入 + plannedEnd 重算）；失败语义照抄既有资源不可用路径。
- [x] 3. 主循环：FIXTURE 的 resourceSequenceMap 与 `runtimeContext.update` 块
  （仅 choice.fixtureId != null 时）。
- [x] 4. `resolveSelectedResources` 增加 fixtureId 参数与 FIXTURE 回填分支；调用点同步。
- [x] 5. 持久化验证：FIXTURE 行经泛化路径落库的测试；仅在实际发现泛化路径缺口时最小修补
  （实测零改动：泛化路径正确落 FIXTURE 行，见执行记录）。
- [x] 6. 测试（design.md Tests Required 1–5 全项，含 child-2 移交的
  `saveMoldCompatibilities` 事务/bump 用例）。
- [x] 7. 文档：`docs/TODO.md` 夹具 P1 行收尾；父任务 implement.md 状态更新。

## Validation Commands

- `cd product-services && mvn -B -ntp clean test`（基准 215 全绿 + 新增用例，记录精确数字）
- 根仓库 `mvn -B -ntp -DskipTests compile`（单体回滚点 28/28）
- 实机冒烟（可选，用户栈在跑勿动）：如需端到端演示，在用户许可的窗口用自己的脚本与端口；
  离线测试足够验收时以离线为准。

## Review Gates

- 算法语义冻结：既有 14 个计算器用例 + 2 个持久化用例 + 路由规则用例必须原样绿；
  非夹具路径（无 FIXTURE 需求行）的排程结果不得有任何变化。
- 失败语义必须复用既有资源不可用路径的风格与文案模式，不新增异常类型。
- 单体/根 pom/根 schema.sql/compose.dev.yml 零改动；新代码构造器注入；无 System.out。
- task_assignment 结构不动；task_assignment_resource 写入走泛化路径。
- 全部勾选后交 trellis-check 校验；通过后父任务按 Task Map 完成收尾（跨子任务集成评审）。

## Rollback Points

- 计算器扩展可独立回滚（无 FIXTURE 需求行时不进入任何新分支）；
- 回滚后系统回到 child-2 基线（兼容数据在库但不参与计算）。

## 执行记录（2026-09-15）

### 改动面

- 修改：`product-services/product-planning/src/main/java/com/product/planning/service/impl/TaskSchedulingCalculator.java`
  （唯一源码改动，+155/-13 行，check 以 --numstat 核实）。插入点：
  - `ResourceChoice`（~1500 行区）：新增 `fixtureId`/`fixtureSequence` 两个 final 字段与构造参数
    （可空，机台分支传选择值、工位分支传 null）。
  - `chooseResources(...)` 机台分支：阶段 3「选择夹具」插在模具阶段之后、人员阶段之前
    （人员阶段注释顺延为阶段 4）；`fixtureNextTime` 纳入既有 `maxTime(...)` 有效开始时间，
    plannedEnd 由既有重算块处理，无新增重算逻辑。
  - 主循环：`choice.fixtureSequence != null` → `resourceSequenceMap.put(FIXTURE, ...)`；
    `choice.fixtureId != null` → `runtimeContext.update(FIXTURE, ...)`（既有逐类型块模式）。
  - `resolveSelectedResources(...)`：新增 `fixtureId` 参数 + FIXTURE 回填分支（仅回填 null）。
  - 新增私有段「夹具选择」：`findMandatoryFixtureReqs` / `chooseFixture` /
    `fixtureSupportsMold`（显式允许清单：isCompatible=1 才兼容；无 MOLD 需求 → 不可满足）。
  - `chooseWorkstationResources`：防御性守卫（工位任务携带强制 FIXTURE 需求 → 按不可排程）。
- 持久化零改动：`appendRequirementAssignments(...)` 泛化路径按解析后需求行写
  task_assignment_resource，FIXTURE 行（含独立序号）经测试证明正确落库，无缺口。
- 新增测试 3 个文件（仅新增，既有测试文件零字节改动）：
  - `product-planning/src/test/.../TaskSchedulingCalculatorFixtureTest.java`（10 例）
  - `product-planning/src/test/.../TaskAssignmentPersistenceServiceFixtureTest.java`（2 例）
  - `product-master-data/src/test/.../service/impl/FixtureServiceImplMoldCompatibilityTest.java`
    （4 例，child-2 移交：成功 bump；saveBatch 抛异常/remove=false 不 bump；null fixtureId 拒绝）

### 失败语义证据

复用既有资源不可用路径，未新增异常类型：夹具不可满足时 `log.warn("任务 {} 没有可用夹具(模具 {})")`
+ `chooseResources` 返回 null → 主循环抛既有 `ServiceException("任务" + taskId + "没有可用资源")`，
与模具/人员不可用完全同构（`chooseMold`/`choosePerson` 返回 null 同路径）。

### 干净全量验证（非记忆值，日志 /tmp/fixture-full-test-run.log）

`cd product-services && mvn -B -ntp clean test`：BUILD SUCCESS，**231 例全绿（0 失败 0 错误 0 跳过）**，
基线 215 + 新增 16。分模块：
cloud-common 21 / cloud-security 11 / cloud-messaging 8 / gateway 17 / identity 31 /
master-data 15（11+4）/ demand-service 27 / planning 84（72+12）/ execution 17。
回归门（原样绿）：TaskSchedulingCalculatorTest 14、TaskAssignmentPersistenceServiceTest 2、
RouteRuleRegistryTest 5、FixtureAwareRouteRuleTest 4、LegacyRouteRuleRegressionTest 4。

根仓库 `mvn -B -ntp -DskipTests compile`：28/28 模块 BUILD SUCCESS（日志
/tmp/fixture-root-compile.log）。单体/根 pom/根 schema.sql/compose.dev.yml 零改动；
task_assignment 结构零改动；无 System.out；未触碰用户 8080/8101-8105 运行栈（纯离线验证）。

### 偏差与发现

- 无计划偏差。一个测试侧发现：既有测试桩日历 `workdayPattern="Mon-Tue-Wed-Thu-Fri-Sat-Sun"`
  走 `isWorkday` 的区间解析分支（`split("-")` 得 7 段 ≠ 2 → 恒非工作日 → `nextWorkday`
  以 guard=7 退出一律 +7 天）；既有用例不断言绝对时间故不可见。新测试断言绝对时间，
  改用可正确解析的 `"Mon,Tue,Wed,Thu,Fri,Sat,Sun"`（全周工作日）并加注释说明。
  该 quirk 属既有共享行为，本任务未改动（算法语义冻结）。
- 最早可用夹具比较沿用 `choosePerson`/`chooseMold` 的既有比较写法（口径一致）。
