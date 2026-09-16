# 成本模型精度提升 — Implementation Plan

> 阅读顺序：implement.jsonl → 本任务 design.md → prd.md →
> `TaskSchedulingCalculator`（比较器区 1122-1175、estimateSetupCost 838、chooseBestMachine）→
> microservices-platform.md / quality-guidelines.md。
> 实现前先跑基线（270 + 10 IT），实现后逐用例对照——算法冻结红线。

## Checklist（按序执行）

- [x] 1. `ProductCostModelProperties`（@ConfigurationProperties + 负值校验）+ yml 默认/注释
  + application.yml env 占位。
- [x] 2. 因子派生私有方法：changeoverCount（MachineLastAssignment/运行时链）、
  crossShiftCount（日历班次切分）；对应 MachineChoice.compositeCost 字段与计算。
- [x] 3. LOWEST_COST 比较器主键切换 + 调用点同步；其余策略/平局键/sameMoldPreferred 不变。
- [x] 4. 单测：因子派生、比较器排序（各因子主导场景）、默认权重与现状等价、配置绑定
  （默认/覆盖/负值拒绝）。
- [x] 5. IT：`LowestCostCompositeCostIT`（换模历史驱动的选择反转 + 默认配置不回归），
  连跑两轮全绿。
- [x] 6. 回归：270 离线 + 既有 10 IT 全绿；单体 `mvn -DskipTests compile` 29/29。
- [x] 7. 文档：README 成本模型小节（因子/权重/量纲/调法）；`docs/TODO.md` P2 行收尾；
  父级无（单任务）。

## Validation Commands

- `cd product-services && mvn -B -ntp clean test`（270 离线基线零回归 + 新增单测）
- `mvn -B -ntp -pl product-integration-tests verify` × 2（IT 栈，连跑全绿）
- 根仓库 `mvn -B -ntp -DskipTests compile`（29/29）

## Review Gates

- 计算器允许触碰区域：仅 LOWEST_COST 比较器主键、compositeCost 计算、因子派生私有方法；
  其余任何行变化 = FAIL。
- 既有 LOWEST_COST 用例结果不变（默认权重下因子不触发即等价）；如有变化默认权重归零该
  因子并记录。
- 单体/根 pom/根 schema.sql/compose.dev.yml 零改动；新代码构造器注入；测试离线可跑。
- 实测进程安全同既有约定；IT transcript 存任务 scratch/。
- 勾选后交 trellis-check 校验。

## Rollback Points

- 权重全 0 即回到"仅换型时间"的现状语义（配置级回滚）；代码级回滚 = 还原本任务提交，
  无 schema/数据迁移。

## 执行记录（2026-09-16，trellis-implement 实测）

### 基线（实现前本人实跑）

- `cd product-services && mvn -B -ntp clean test`：**270 全绿**（EXIT=0，BUILD SUCCESS）。
  分模块：cloud-common 21 / cloud-security 11 / cloud-messaging 8 / gateway 17 /
  identity 31 / master-data 20 / demand-service 27 / **planning 112** / execution 23。
- 根仓库 `mvn -B -ntp -DskipTests compile`：**29/29** BUILD SUCCESS（EXIT=0）。
- IT 基线 10 用例：原计划在旧栈实跑补录；实测前离线 clean test 已删除运行中 IT 实例的
  fat-jar（`mvn clean` 删除 target/，运行中 JVM 懒加载服务文件失败 → identity 登录 500，
  see 下文"栈事件"），旧栈已不可复验。改以两项更强证据替代：① 源码口径 3+4+3=10 用例；
  ② 新栈两轮 verify 中既有 10 用例（3+4+3）全绿（同一代码路径零回归）。

### 落地内容

1. `product-planning/config/ProductCostModelProperties.java`（新增）：
   `@ConfigurationProperties("product.pps.schedule.cost-model")`，默认 1/30/60/0；
   `InitializingBean.afterPropertiesSet()` 负值校验（启动失败，非静默取 0）。
2. `product-planning/src/main/resources/application.yml`：`product.pps.schedule.cost-model`
   四键 yml 默认 + `PRODUCT_PPS_SCHEDULE_COST_MODEL_*` env 占位 + 量纲注释。
3. `TaskSchedulingCalculator`（仅允许区域）：
   - LOWEST_COST 比较器主键 `setupCostMin`（Integer+nullsLast）→ `compositeCost`
     （`comparingLong`）；平局键 plannedEnd/plannedStart/machineId 逐字未动；
     其余三策略分支逐字未动。
   - `MachineChoice` 增加 `compositeCost` 字段 + 构造参数；`chooseBestMachine` 逐候选
     计算（仅 `strategy == LOWEST_COST` 时计算，其余策略零开销零参与）。
   - 新增因子派生私有方法区：`computeCompositeCost`（能耗项显式 ×0 预留）、
     `deriveChangeoverCount`（MachineLastAssignment 单槽链 + `requiresChangeover` +
     `resolvePlannedMoldId` 实际字段派生：有历史记 1、本次 setup 类再换模 +1、无历史 0）、
     `triggersMoldChange`（与 ChangeoverCalculator「双方同模才算同模」口径一致）、
     `deriveCrossShiftCount`（每工作日 shiftStart/shiftEnd 边界严格内含计数，端点不计，
     无日历/缺字段/解析失败 → 0）。
   - 权重注入：`@Autowired ProductCostModelProperties costModelProperties`（类既有字段
     注入风格；直建实例单测 null 时回退 new 默认值）——见"偏差"①。
4. 单测 19 个（离线）：`ProductCostModelPropertiesTest`（5：默认值/kebab 覆盖/env 覆盖/
   负值拒绝/0 合法）+ `TaskSchedulingCostModelTest`（14：换模次数派生 5、跨班次计数 4、
   综合成本组合 2、比较器主键+平局键 1、换模历史反转选择 1、平局回归 1）。
5. IT：`LowestCostCompositeCostIT`（2 用例，@Order(4) 注册进 `IntegrationSuiteIT`）。
   候选池封闭机制：INJECT 任务 MOLD 兼容裁决——仅本套件 4 台机台持有与本套件模具的兼容行，
   IT 段外共享库 AVAILABLE 机台自动失格（不改写他人数据面；段外机台存在性仅 transcript 记录）。
   轮 1 反转：743（默认换型 0 + 换模历史 1 次 → 0+30×1=30）vs 744（默认换型 20 无历史 → 20），
   旧主键会选 743，新主键选 744；窗口 D 08:00-09:00 不变。轮 2 默认不回归：
   745（默认 10）胜 746（45），与切换前排序一致。

### 实测数字（clean run，非记忆）

- 实现后 `cd product-services && mvn -B -ntp clean test`：**289 全绿**
  （planning 131 = 112 既有 + 19 新增；其余模块与基线一致：21/11/8/17/31/20/27/23）。
- IT（新栈，it-up.sh 重建 jar + 重启，仅杀 pids.txt 记录 PID）：
  `mvn -B -ntp -pl product-integration-tests verify` × 2 连跑全绿，各 **12 用例
  （10 既有 + 2 新增）0 失败**（run A：EXIT=0 / run B：EXIT=0，BUILD SUCCESS）；
  transcript 复制至任务 `scratch/lowcost-it/`（102252=run A、102457=run B）。
- 根仓库 `mvn -B -ntp -DskipTests compile`：实现后复跑 29/29 BUILD SUCCESS。

### 进程安全（实测）

- 用户栈 8080/8101-8105 未触碰；IT 实例经 `it-down.sh`（仅杀 scratch/integration-tests/
  pids.txt 记录的 6 个 PID）→ `it-up.sh`（新 PID 全量记录同文件，端口 8280/8201-8205、
  PRODUCT_IT_GROUP/Redis db5/vhost it 四件套隔离）。

### 栈事件（如实记录）

离线基线 `mvn clean test` 删除了运行中 IT 栈实例的 fat-jar 文件（Linux 下旧 inode 仍可
读，故健康检查短暂仍 UP），identity 实例登录时懒加载 jjwt 服务文件失败（500
ServiceConfigurationError），旧栈 10 用例基线补录因此失败。处置：按既有流程 it-down →
it-up 全量重建+重启（新代码随之生效），未触碰用户栈与 infra 容器。

### 偏差与决议

1. **计算器新增 `@Autowired ProductCostModelProperties` 字段 + import（2 处非算法行）**：
   权重配置进入 compositeCost 计算的最小通道；置于既有字段注入风格（类内已有 3 个
   @Autowired 字段）。若按"任何其他行变化 = FAIL"字面执行则配置化（KD2）不可实现，
   故记录为经设计的最小偏差；比较器/平局键/其余策略/任务排序/时间计算零变化。
2. **既有 LOWEST_COST 用例默认权重对照**：唯一 LOWEST_COST 离线用例
   （`calculateBatchAssignmentsShouldPreferLowestCostMachineWhenRequested`，双机台无换模
   历史、窗口单班次内）下 changeoverCount=0、crossShiftCount=0 → compositeCost ==
   setupCostMin，结果不变；**无任何因子默认权重归零**（默认 1/30/60/0 全部生效）。
3. **changeoverCount 口径**（design.md"以两处实际字段为准"的落地）：MachineLastAssignment
   链为单槽快照（只有最近一次派工），无法重建多次历史换模计数；按实际字段派生为
   「链上有历史记 1 + 本次 setup 类选择再触发换模再计 1（ChangeoverCalculator 同模判定
   口径）+ 无历史 0」；多任务运行中的多次换模经快照逐任务覆盖自然反映在"本次选择是否
   再换模"的判定中。
4. **IT 候选池门控从硬断言改为兼容裁决**：首跑发现共享库存在 IT 段外 AVAILABLE 机台
   （用户数据面，禁改），改用 INJECT 模具兼容行封闭候选池（对预期不妥协，未改写他人数据）。
