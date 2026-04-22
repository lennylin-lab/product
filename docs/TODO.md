# 项目优化记录

## 已完成优化

- 在选择大量批次生成任务时，使用 `CompletableFuture` 并行查询批次与任务，减少批量校验耗时
- 使用 `Map` 进行批次索引，在遍历 ID 集合时通过键值查找替代线性遍历，将复杂度从 `O(n)` 降到 `O(1)`
- 外部化 `product-server` 的 Redis、MySQL、Token 和 AliOSS 敏感配置，避免仓库内保留明文密钥
- 排程策略已支持按参数切换最早开始、最早完工、交期优先三种模式
- 待排任务在进入排程前会统一做空值过滤、重复任务去重、已派工任务排除，并按 `earliestStart + batchId + sequence + taskId` 稳定排序

## 待优化项

| 方向 | 说明 |
| --- | --- |
| 排程策略 | 排程时可增加“最早结束”“最低成本”等决策 |
| 资源模型 | 资源状态与排程占用的职责边界需要进一步梳理 |

## 开发任务表

以下任务为当前阶段建议优先推进的开发项，已按模块拆分，便于直接进入迭代或转 Issue。

| 模块 | 开发任务 | 优先级 | 主要位置 | 验收标准 |
| --- | --- | --- | --- | --- |
| `product-server` | 外部化敏感配置，移除明文凭据 | P0 | `product-server/src/main/resources/application.yml` | 仓库内不再出现 OSS、数据库、Redis 等明文敏感配置，且可通过环境变量或外部配置启动 |
| `product-pps` | 抽象排程策略，支持策略切换 | P1 | `TaskSchedulingCoordinator.java`、`TaskSchedulingCalculator.java` | 同一批任务可通过参数切换最早开始、最早完工、交期优先等策略 |
| `product-pps` | 扩展资源模型与协同约束 | P1 | `TaskSchedulingQueryService.java`、`TaskSchedulingCalculator.java` | 排程结果能体现机台、模具、人员/工位的协同约束与换型时间 |
| `product-pps` | 修正待排任务排序与边界行为（已完成） | P2 | `TaskSchedulingQueryService.java` | 待排任务排序不再依赖无序 UUID；空任务、重复派工、无可用机台有明确处理 |
| `product-pps` | 优化排程编排与进度职责 | P2 | `TaskSchedulingCoordinator.java` | 协调器只负责编排，进度阈值与推送规则可独立调整 |
| `product-execute` | 补齐任务事件与状态闭环 | P1 | `TaskEventServiceImpl.java`、`TaskEventController.java` | 任务完工后可联动批次/订单状态，异常、暂停、恢复事件可完整记录并参与处理 |
| `product-demand` | 规范订单状态流转与排程入口约束 | P1 | `CustomerOrderServiceImpl.java` | 已投入生产或已完成订单不可被错误修改，订单状态与生产链路一致 |
| `product-domain` | 补齐排程与执行领域模型 | P1 | `product-domain/src/main/java/com/product/domain/entity/**` | 资源、兼容关系、换型、异常事件、状态联动所需字段补齐 |
| `docs` | 把需求说明书补成可验收文档 | P2 | `docs/生产计划与排程计划系统需求说明书.md` | 文档包含业务背景、角色、流程、功能清单、非功能需求、验收标准、边界条件 |
| `product-pps` / `product-execute` | 补核心测试 | P2 | `src/test/java` | 排程、事件流转、异常场景、重复派工、跨班次等关键分支有测试覆盖 |

## 建议执行顺序

1. 先做 `P0`，消除安全风险。
2. 再做 `P1` 的排程策略、资源模型和执行闭环。
3. 然后补 `P2` 的测试和文档。
4. 最后做编排职责优化，进一步收敛复杂度。
