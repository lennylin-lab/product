# 项目优化记录

> 最后校准时间：`2026-09-15`  
> 本文档用于反映当前真实进度，不再保留已被实现覆盖的历史待办。自 2026-09-14 起项目整体迁移至 `product-services/` 微服务体系，旧单体模块代码保留作对照、冻结演进。

## 已完成

### 性能与基础（2026-04-25 校准轮）

- 在选择大量批次生成任务时，使用 `CompletableFuture` 并行查询批次与任务，减少批量校验耗时。
- 使用 `Map` 进行批次索引，在遍历 ID 集合时通过键值查找替代线性遍历，将复杂度从 `O(n)` 降到 `O(1)`。
- 外部化 Redis、MySQL、Token 和 AliOSS 敏感配置，避免仓库内保留明文密钥；演练用 JWT 私钥已移出仓库（`jwt-keys/` 已 ignore）。

### 排程能力（2026-08 ~ 09）

- 排程策略支持按参数切换 `EARLIEST_START`、`EARLIEST_FINISH`、`DUE_DATE_PRIORITY`、`LOWEST_COST` 四种模式。
- 待排任务进入排程前统一做空值过滤、重复任务去重、已派工任务排除，并按 `earliestStart + batchId + sequence + taskId` 稳定排序。
- `product-domain` 资源协同领域模型补齐：模具、机模兼容、资源能力、产品模具参数、换型规则、任务资源需求等实体。
- 多资源排程落地（2026-08-30）：人员、工位随任务资源需求进入级联选择（`MACHINE -> MOLD -> PERSON / WORKSTATION -> PERSON`）与占用计算，分配结果回写需求并落 `task_assignment_resource` 明细。
- 任务依赖约束进入排程，并完成工时口径与资源明细清理（2026-08-30 P0 优化）。
- 工艺路线闭环（2026-09-06）：规则注册表、路线管理 API、产品绑定，`queue_policy`（含 `QUEUE_SAME_MOLD_FIRST` 同模具优先）与标准工时模型进入排程。
- `LOWEST_COST` 成本口径：任务换型时间 → 资源需求 `changeoverTimeMin` → 机台 `defaultSetupTimeMin` 三级取值（能耗等综合因子仍未纳入，见剩余缺口）。

### 执行与需求闭环（2026-04-27 校准轮）

- `product-execute` 把 `start/pause/resume/complete` 事件接入状态刷新链路，事件成功后驱动批次、订单行、订单状态联动。
- `product-demand` 统一订单状态守卫，`check`、`cancelCheck`、`updateCustomerOrder` 共用状态校验逻辑，已投产和已完成订单不能被旁路修改；手工状态仅允许 `NEW/CONFIRMED` 间流转。

### 微服务迁移与事件一致性（2026-09-14 ~ 09-15，phase 0-6）

- Spring Cloud Alibaba 微服务迁移完成：现役代码迁至 `product-services/`，网关 8080 唯一入口，identity(8101)/master-data(8102)/demand-service(8103)/planning(8104)/execution(8105) 五个业务服务 + cloud-common/cloud-security/cloud-messaging 共享库；排程引擎与事件服务同构迁移并通过 parity 验证。
- 事件一致性骨架：事务性 Outbox + 中继、`eventId` 幂等消费、死信队列审计、事件重放与运维对账端点；定义 `task.status.changed`、`resource.status.changed`、`batch.progress.changed`、`order_line.progress.changed` 四类跨服务事件。
- 资源状态事件（资源故障等）登记链路：经内部端点入库并 outbox 出站，planning 侧幂等消费记录（当前仅记录/告警，不驱动状态机）。
- SpringDoc OpenAPI（Swagger）接入，网关按服务聚合 `/v3/api-docs`；接口清单沉淀在 README「API接口」章节。

### 测试与文档

- 测试扩充至约 62 个测试类（单微服务体系约 49 个 + 单体约 13 个），其中 12 个服务级 `@SpringBootTest` 安全/契约测试，覆盖网关、身份、主数据、需求、排程、执行；排程单测覆盖级联选资源、人员可用窗口、工位排程、任务依赖、同模具优先等。
- 文档沉淀：《微服务运维手册》《数据补偿手册》、需求说明书主干、README API 接口清单；需求说明书只保留需求与验收，进度统一由本文件跟踪（原第十章“边界与未完成项”已移除）；README 已重写为微服务视角（架构、快速开始、API、配置，单体内容标注为历史对照）。

## 当前状态

| 模块 | 当前状态 | 主要位置 | 说明 |
| --- | --- | --- | --- |
| `product-services` | 现役，已统一切换 | `product-services/product-gateway` 等 | 网关 8080 唯一入口，五业务服务 + 三个共享库；基础设施含 Nacos/RabbitMQ/Jaeger/MySQL/Redis/ELK |
| `product-planning` | 排程现役 | `com.product.planning.service.impl.TaskSchedulingCalculator` | 覆盖机台/模具/人员/工位选择与占用、任务依赖、工艺路线 `queue_policy`、四种策略；换型成本仍取任务需求或机台默认准备时间 |
| `product-execution` | 执行事件现役 | `com.product.execution.*` | `START/PAUSE/RESUME/FINISH` 四类事件（值冻结）+ 资源状态事件记录；事件经 cloud-messaging outbox 出站 |
| `product-cloud-messaging` | 事件一致性骨架 | `product-services/product-cloud-messaging` | Outbox/幂等消费/死信审计/重放已就绪；异常事件建模与重排触发未做 |
| 旧单体模块（`product-pps`/`product-execute`/`product-demand` 等） | 代码保留，冻结演进 | 仓库根目录各模块 | 自 2026-09-15 起不再是主线，仅作迁移对照；新功能一律在 `product-services` 落地 |
| `docs` | 主干可用 | `docs/**`、Swagger | 需求说明书、运维手册、补偿手册、接口清单已沉淀；页面稿与业务操作手册仍缺 |

## 剩余缺口

| 优先级 | 模块 | 剩余任务 | 主要位置 | 说明 |
| --- | --- | --- | --- | --- |
| `P1` | `product-domain` / `product-planning` | 夹具等协同资源：从建模到排程 | 资源相关实体、`TaskSchedulingCalculator.java` | 人员、工位已进排程；夹具（FIXTURE）连领域模型都没有，需建模、兼容规则、分配与占用计算逐层补齐 |
| `P1` | `product-execution` / `product-planning` | 异常事件建模与重排触发 | `TaskEventController`、事件消费者、排程入口 | 事件类型仍冻结四种；异常、报工失败未建模；资源状态事件仅记录不驱动状态机；尚无重排触发链路 |
| `P2` | `product-planning` | 提升成本模型精度 | `TaskSchedulingCalculator.estimateSetupCost` | `LOWEST_COST` 目前仅基于换型/准备时间，未纳入能耗、换模次数、跨班次损耗等综合因子 |
| `P2` | `product-services/**/src/test` | 补系统级与集成级测试 | planning、execution、demand-service | 服务级契约测试已就位；跨服务数据库状态联动、跨班次排程、并发排程场景仍缺 |
| `P3` | `docs` | 页面说明与业务操作手册 | `docs/**` | 运维/补偿手册已有；页面稿、面向业务的操作手册未沉淀（README 已于 2026-09-15 重写为微服务视角，单体内容降级为历史对照） |

## 建议执行顺序

1. 后续功能一律在 `product-services` 微服务体系落地，旧单体模块仅作对照，不再加功能。
2. 把夹具从「无模型」推进到「可参与排程计算」：建模 → 兼容规则 → 分配与占用。
3. 补异常事件、报工失败建模，打通资源状态事件到状态机刷新与重排触发的链路。
4. 补系统级测试与验收用例，验证跨服务状态一致性和跨班次场景。
5. 最后细化成本模型，补页面说明与业务操作手册。
