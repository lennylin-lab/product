# Technical Design

## 1. Architecture Baseline

当前 14 个 Maven 模块由 `product-server` 聚合为一个 Spring Boot 进程，共享 MySQL schema、Redis、领域实体、框架配置和进程内事务。主要跨边界耦合包括：

- `product-auth` 直接调用 `product-system-api` 获取用户、角色、菜单和权限。
- `product-demand` 直接调用 `product-pps` 维护产品工艺路线。
- `product-execute` 通过进程内状态刷新链路联动任务、批次、订单行和订单。
- 排程同时读取订单、产品工艺、资源日历、任务和派工数据。

目标不是把 Maven 模块逐个变成服务，而是建立少量自治业务边界。项目未上线，采用同一开发周期内一次性重构，验收通过后统一切换，不实现双写或单体兼容入口。

## 2. Target Services

| Service | Responsibility | Owned data |
| --- | --- | --- |
| `product-gateway` | 统一入口、路由、CORS、基础限流、Token 前置校验、请求追踪 | 无业务表 |
| `product-identity` | 登录、验证码、用户、角色、菜单、字典、JWT 签发与权限查询 | `sys_*`；验证码/会话缓存命名空间 |
| `product-master-data` | 产品、工艺路线、工序规则、资源、机台、模具、能力、日历和换型规则 | `product`、`product_*`、`route_*`、`resource*`、`machine*`、`mold*`、`calendar`、`changeover_rule` |
| `product-demand` | 客户、订单、订单行及订单生命周期 | `customer`、`customer_order`、`order_line` |
| `product-planning` | 生产批次、工序任务、资源需求、派工、排程任务与算法 | `production_batch`、`operation_task`、`task_*`、`schedule_job`、`order_line_allocation` |
| `product-execution` | 任务执行事件、资源状态事件、现场追溯 | `task_event`、`resource_status_event` |

`product-common` 仅保留稳定、无业务语义的技术基础；各服务拥有自己的 API contract，禁止共享 MyBatis Entity。`product-become` 定位为开发期代码生成工具，不作为生产运行服务。文件上传能力在首版由网关转发至明确的拥有服务；若后续形成独立文件生命周期，再拆文件服务。

## 3. Infrastructure Choices

| Concern | Choice | Decision |
| --- | --- | --- |
| Version management | Spring Boot + Spring Cloud + Spring Cloud Alibaba BOM | 实施开始时查官方兼容矩阵并做最小启动验证；当前不臆测具体版本。若 Boot 3.5 无受支持组合，先调整 Boot 到兼容版本，不绕过 BOM 强行混搭。 |
| Registry/config | Nacos | 同时提供服务发现和配置管理；按环境使用 namespace、按服务使用 group/Data ID。密钥不进入普通配置，生产使用环境注入或外部 Secret。 |
| Edge routing | Spring Cloud Gateway | 唯一外部入口，保持现有主要 URL，统一鉴权前置、限流和 trace context。 |
| Synchronous RPC | Spring Cloud OpenFeign + LoadBalancer | 用于必须立即返回的只读查询或命令确认；契约 DTO 独立，设置超时且禁止无界重试。 |
| Traffic governance | Sentinel | 网关与服务热点限流、熔断和降级；规则由 Nacos 持久化。 |
| Async messaging | RabbitMQ | 承载状态联动、领域事件与最终一致性；使用持久化 exchange/queue、publisher confirm、手动 ack、重试与死信，生产者采用事务内 Outbox，消费者按 eventId 幂等。当前吞吐与回放需求不需要 Kafka。 |
| Distributed transaction | Deferred Seata | 默认不引入。服务内使用本地事务，跨服务使用事件、状态机和补偿；仅在识别出无法重划边界且必须强一致的短事务后单独评审 Seata。 |
| Cache/lock | Redis + Redisson where justified | 每服务使用独立 key prefix/ACL；缓存不可作为跨服务事实源，分布式锁只保护明确竞争资源。 |
| Database | MySQL, database/schema per service | 可共享一个 MySQL 实例以降低开发运维成本，但账号和 schema 隔离；目标态禁止跨 schema join/写入。 |
| Observability | Micrometer Observation + OpenTelemetry, Prometheus/Grafana, ELK, Tempo | 指标、日志、追踪使用统一 service/env/traceId 标签；沿用 ELK 日志资产，补充指标与链路追踪。 |
| API docs | springdoc per service + gateway aggregation | 每个服务发布 OpenAPI，网关聚合开发文档。 |
| Deployment | Docker Compose for development; Kubernetes deferred | 先扩展本地 Compose 完成可重复验收。没有明确生产平台前不提前引入 Kubernetes。 |

## 4. Communication And Contracts

- 外部请求只进入 Gateway。Gateway 验证 JWT 签名与基本声明，服务端仍执行权限校验；内部调用传递用户上下文和 trace context，不信任客户端伪造的内部头。
- 登录、权限加载由 Identity 同步提供。建议保留现有 JWT 语义并引入非对称签名/JWK；Gateway 和各服务本地验签，减少每请求远程鉴权。
- Demand 创建或变更产品关联时，不再直接调用 Planning。产品与工艺归 Master Data；Demand 保存产品标识并按需同步校验。
- Planning 对一次排程所需的订单与主数据采用版本化快照/批量查询，避免算法循环内产生远程 N+1 调用。排程结果只由 Planning 写入。
- Execution 接收开始、暂停、恢复、完成和异常命令，在本地事务写事件；随后通过 RabbitMQ 发布版本化领域事件。Planning 消费任务状态事件，Demand 消费批次/订单进度事件，各自更新自身状态。
- 事件 envelope 至少包含 `eventId`、`eventType`、`version`、`occurredAt`、`producer`、`aggregateId`、`correlationId` 和 payload。消费者记录去重键，支持乱序检测、重试、死信和人工重放。
- Feign 契约明确超时、错误码和降级行为。写命令默认不自动重试；读请求仅在幂等且总超时受控时有限重试。

## 5. Data And Consistency

统一切换前将 `schema.sql` 按所有权拆为服务 schema，并为每个服务建立独立数据库账号。初始化数据和外键关系随所有权迁移；跨服务外键改为业务 ID 与应用层校验。

一致性策略：

1. 服务内聚合使用本地 ACID 事务。
2. 事务内写业务数据与 Outbox；独立发布器投递 RabbitMQ，并在 publisher confirm 成功后标记已发布。
3. 消费者幂等更新本地状态并记录消费结果。
4. 定时对账检查订单、批次、任务和执行状态，异常进入补偿队列或人工处理。
5. 不允许通过共享数据库、跨 schema join 或共享 Entity 绕开契约。

因项目未上线，不做在线数据双写。若开发库已有需保留数据，统一切换前执行一次离线备份、迁移、校验；失败时恢复旧库和旧构建产物。

## 6. Security And Operations

- Identity 负责凭证与 Token 生命周期；密码、Nacos/MySQL/Redis/RabbitMQ 凭据使用环境 Secret，不提交仓库。
- Gateway 配置公网暴露面；Nacos、数据库、Redis、RabbitMQ 和内部服务端口默认仅内网访问。
- 服务间最小权限数据库账号，管理端点单独保护；健康检查分 liveness/readiness。
- 日志不得打印 Token、密码或个人敏感字段；审计记录登录、权限变更和关键生产操作。
- 统一错误模型、correlationId、指标命名与告警基线。关键告警包括错误率、P95 延迟、线程/连接池、MQ 堆积、Outbox 滞留、消费失败和数据对账差异。

## 7. Compatibility And Cutover

- Gateway 尽量保持现有 API 路径与返回结构；契约测试记录允许的差异。
- 统一切换前完成全量构建、单元/集成/契约/E2E、故障注入、迁移演练与容量基线。
- 切换步骤为：冻结结构变更 -> 备份 -> 初始化/迁移服务 schema -> 数据校验 -> 启动中间件和服务 -> E2E 冒烟 -> 开放入口。
- 回滚触发条件包括核心链路失败、数据校验不一致、认证不可用或关键 SLO 未达标；回滚恢复旧数据库备份与原单体构建。因为尚未上线，不建设运行期双轨流量回切。

## 8. Key Risks

- Spring Boot 3.5 与 Spring Cloud Alibaba 的兼容组合必须在实施时以官方矩阵和实际启动测试确认。
- 当前共享 `product-domain` 容易演变为跨服务共享数据库模型，必须拆为服务内模型和独立契约。
- 状态刷新链由同步进程内调用改为事件最终一致后，需明确状态机版本、乱序和补偿语义。
- 排程算法对多域数据依赖较重，若直接细粒度 RPC 会显著降低性能；必须使用批量契约和排程输入快照。
