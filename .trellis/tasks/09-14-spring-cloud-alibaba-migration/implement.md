# Implementation Plan

> 本文是后续实施计划。本任务当前只完成规划并推进状态，不执行以下代码变更。

## Phase 0: Architecture Gate

- 进入条件: 本规划经用户最终批准；产品代码相对规划任务开始前无变更（AC8）。

- [ ] 根据官方兼容矩阵选定 Spring Boot、Spring Cloud、Spring Cloud Alibaba 与 JDK 组合，并用空白最小应用验证 Nacos Discovery/Config、Gateway、Feign、Sentinel 和 Spring AMQP/RabbitMQ 可启动。
- [ ] 建立 Architecture Decision Records：服务边界、JWT/JWK、事件一致性、数据库隔离、版本基线。
- [ ] 冻结外部 API 基线、数据库基线和核心 E2E 用例。
- Validation: BOM 依赖树无冲突；最小应用启动测试通过；ADR 和契约基线评审通过。
- Rollback point: 不修改业务模块；版本组合不兼容时调整基线后重测。

## Phase 1: Platform Skeleton

- [ ] 重组父 POM 与公共依赖管理，创建 Gateway 与五个业务服务（identity、master-data、demand、planning、execution）共六个启动骨架。
- [ ] 建立 Nacos namespace/group/Data ID 规范和本地 Compose 基础设施。
- [ ] 建立统一错误契约、请求上下文、OpenAPI、健康检查、日志、指标和追踪基线。
- [ ] 建立 CI 构建、镜像和服务级测试框架。
- Validation: 所有空骨架独立启动、注册、健康检查、经 Gateway 路由；配置隔离和 observability smoke test 通过。
- Rollback point: 保留原模块与启动方式，骨架未通过不得迁移业务。

## Phase 2: Identity And Gateway

- [ ] 将认证、用户、角色、菜单、字典归入 Identity，拆出独立 schema 和缓存命名空间。
- [ ] 将 JWT 改为可供 Gateway/服务本地验证的签名与密钥发布机制，建立内部用户上下文防伪约束。
- [ ] 配置 Gateway 路由、权限前置、CORS、Sentinel 和统一错误响应。
- Validation: 登录、验证码、Token 刷新/过期、菜单权限、匿名接口、越权和限流契约测试通过。
- Rollback point: Identity 数据迁移脚本可重复；失败恢复身份库备份和原认证构建。

## Phase 3: Master Data And Demand

- [ ] 按设计迁移主数据及订单域，消除 `demand -> pps` Java 依赖。
- [ ] 建立产品/工艺/资源批量查询契约，定义数据版本字段。
- [ ] 拆分 schema、Mapper、Entity 和 API DTO；禁止共享持久化模型。
- Validation: 主数据 CRUD、订单全生命周期、权限、分页/导入导出和跨域引用校验测试通过。
- Rollback point: 每个 schema 独立初始化和校验；迁移失败可清库重建或恢复备份。

## Phase 4: Planning

- [ ] 迁移批次、工序任务、资源需求、派工、异步排程任务与算法。
- [ ] 使用批量契约和版本化输入快照加载 Demand/Master Data，避免 N+1 RPC。
- [ ] 建立排程幂等、超时、并发互斥和结果一致性规则。
- Validation: 现有排程单测全部迁移；补充服务集成、跨班次、并发、超时、远程依赖失败和基准性能测试。
- Rollback point: 对同一固定数据集比较单体与微服务排程结果；关键差异未解释前不得进入下一阶段。

## Phase 5: Execution And Event Consistency

- [ ] 迁移任务/资源事件，建立 RabbitMQ exchange/queue/binding、版本化 envelope、publisher confirm、Outbox、幂等消费、重试和死信。
- [ ] 将进程内状态刷新链改为 Planning/Demand 各自消费事件并更新本域状态。
- [ ] 建立对账、补偿和人工重放工具及审计记录。
- Validation: 开始/暂停/恢复/完成/异常全链路测试；重复、乱序、延迟、MQ 暂停恢复、消费者失败和补偿测试通过。
- Rollback point: 可清理测试消息并从备份重建服务库；状态对账不为零不得统一切换。

## Phase 6: Unified Cutover

- [ ] 从 Gateway 入口执行完整“登录 -> 订单 -> 批次 -> 排程 -> 执行 -> 完工”E2E。
- [ ] 执行安全、故障注入、容量、日志指标追踪和告警验收。
- [ ] 演练离线备份、schema 初始化/迁移、数据校验、启动顺序和整套回滚。
- [ ] 更新开发运行、部署、故障处理和数据补偿文档。
- Validation: `mvn verify`、服务集成与契约测试、E2E、Compose clean-room 启动、迁移校验和回滚演练全部通过。
- Rollback point: 关闭微服务入口，恢复旧库备份和原单体构建；问题修复后重新进行完整统一切换演练。

## Review Gates

- 每阶段需验证没有跨服务数据库访问、共享 Entity、无界重试或同步调用环。
- 每个 RPC 和事件必须有契约测试、超时/错误语义、owner 和版本策略。
- 每个状态变更必须能追溯到命令或事件，并通过对账验证最终一致。
- 未完成官方版本兼容验证、核心 E2E、数据迁移与回滚演练时不得视为完成。

## Risky Areas

- `product-domain` 的共享实体拆分。
- `product-framework` 与 `product-core` 中安全、状态刷新和全局配置的服务化解耦。
- `product-demand` 与 `product-pps` 的产品工艺直接调用。
- `product-execute` 到任务、批次和订单的跨域状态闭环。
- `schema.sql` 的数据所有权拆分及跨表查询替换。
