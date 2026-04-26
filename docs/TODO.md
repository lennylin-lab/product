# 项目优化记录

> 最后校准时间：`2026-04-25`  
> 本文档用于反映当前真实进度，不再保留已被实现覆盖的历史待办。

## 已完成

- 在选择大量批次生成任务时，使用 `CompletableFuture` 并行查询批次与任务，减少批量校验耗时。
- 使用 `Map` 进行批次索引，在遍历 ID 集合时通过键值查找替代线性遍历，将复杂度从 `O(n)` 降到 `O(1)`。
- 外部化 `product-server` 的 Redis、MySQL、Token 和 AliOSS 敏感配置，避免仓库内保留明文密钥。
- 排程策略已支持按参数切换 `EARLIEST_START`、`EARLIEST_FINISH`、`DUE_DATE_PRIORITY`、`LOWEST_COST` 四种模式。
- 待排任务在进入排程前会统一做空值过滤、重复任务去重、已派工任务排除，并按 `earliestStart + batchId + sequence + taskId` 稳定排序。
- `product-domain` 已补齐资源协同领域模型，新增模具、机模兼容、资源能力、产品模具参数、换型规则、任务资源需求等实体，并在任务、资源、机台、派工对象上预留接入位。
- `product-pps` 已能读取任务资源需求与机台兼容关系，在机台选择阶段过滤不兼容资源，并把换型时间接入 `LOWEST_COST` 策略。
- `product-execute` 已把 `start/pause/resume/complete` 事件接入状态刷新链路，事件成功后可驱动批次、订单行、订单状态联动。
- `product-demand` 已统一订单状态守卫，`check`、`cancelCheck`、`updateCustomerOrder` 共用状态校验逻辑，已投产和已完成订单不能被旁路修改。
- 已补充 `product-pps`、`product-execute`、`product-demand` 关键单元测试，覆盖资源约束排程、最低成本策略、任务事件闭环、订单状态守卫与若干失败分支。

## 当前状态

| 模块 | 当前状态 | 主要位置 | 说明 |
| --- | --- | --- | --- |
| `product-domain` | 已完成本轮资源模型补强 | `com.product.domain.entity` | 已具备继续扩展人员、工位、模具等协同约束的模型基础 |
| `product-pps` | 已完成资源约束接入与最低成本策略 | `TaskSchedulingQueryService.java`、`TaskSchedulingCalculator.java` | 当前已覆盖机台与模具硬约束，换型成本先采用任务需求或机台默认准备时间 |
| `product-execute` | 已完成任务事件到业务状态闭环 | `TaskEventServiceImpl.java` | 完工、暂停、恢复会触发批次、订单行、订单状态刷新 |
| `product-demand` | 已完成订单状态守卫收敛 | `CustomerOrderServiceImpl.java` | 手工状态仅允许 `NEW/CONFIRMED` 间流转 |
| `docs` | 已补齐需求说明书主结构 | `docs/生产计划与排程计划系统需求说明书.md` | 文档已可用于评审和后续验收拆解 |

## 剩余缺口

| 优先级 | 模块 | 剩余任务 | 主要位置 | 说明 |
| --- | --- | --- | --- | --- |
| `P1` | `product-pps` / `product-domain` | 扩展真实协同资源占用 | `TaskSchedulingCalculator.java`、资源相关实体 | 当前已落地机台与模具约束，但人员、工位、夹具等协同资源仍停留在模型层，尚未进入分配与占用计算 |
| `P1` | `product-execute` / `product-demand` | 扩展异常事件与资源状态闭环 | `TaskEventServiceImpl.java`、状态刷新器 | 当前闭环覆盖开始、暂停、恢复、完工；异常、报工失败、资源故障等事件尚未建模 |
| `P2` | `product-pps` | 提升成本模型精度 | `TaskSchedulingCalculator.java` | `LOWEST_COST` 目前基于换型/准备时间，尚未纳入能耗、换模次数、跨班次损耗等综合因子 |
| `P2` | `src/test/java` | 补系统级与集成级测试 | `product-pps`、`product-execute`、`product-demand` | 当前主要是服务层单测，仍缺跨模块数据库状态联动、跨班次排程和并发排程场景 |
| `P3` | `docs` | 收敛接口与页面级说明 | `docs/**` | 需求说明书已形成主干，但接口清单、页面稿和操作手册仍未沉淀 |

## 建议执行顺序

1. 先把人员、工位、夹具等协同资源从“有模型”推进到“可参与排程计算”。
2. 再补异常事件、资源状态、重排触发等执行侧闭环，形成更完整的事件链。
3. 然后补系统级测试与验收用例，验证跨模块状态一致性和跨班次场景。
4. 最后再继续细化成本模型、接口文档和操作文档。
