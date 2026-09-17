# product-services — Spring Cloud Alibaba 微服务体系

> 依据：`adr/0001-version-baseline.md`（JDK 17 / Boot 3.5.16 / SC 2025.0.3 / SCA 2025.0.0.0 / Nacos 3.0.x / Sentinel 1.8.9）、
> `adr/0002-service-boundaries.md`（5 服务 + 1 网关）。Phase 6 统一切换完成：全部业务已迁移，
> Phase 1 冒烟前缀路由移除，路由/限流/文档聚合/OTLP 为收敛后形态（见 §统一切换基线）。
> 运维（启动顺序/备份恢复/回滚/故障处理）：`docs/微服务运维手册.md`；数据补偿：`docs/数据补偿手册.md`。

## 模块与端口

| Maven 模块 | `spring.application.name`（= Nacos 注册名） | 端口 | 职责 |
| --- | --- | --- | --- |
| product-gateway | `product-gateway` | 8080 | 唯一入口、路由、CORS、限流（Nacos 持久化规则）、JWT 前置校验、trace 注入 |
| product-identity | `product-identity` | 8101 | 登录、验证码、JWT 签发、用户/角色/菜单/字典/权限、服务身份令牌 |
| product-master-data | `product-master-data` | 8102 | 产品/工艺路线/资源/机台/模具/日历/换型规则、数据版本计数 |
| product-demand-service | `product-demand` | 8103 | 客户/订单/订单行及订单生命周期、批次投影消费、订单状态聚合 |
| product-planning | `product-planning` | 8104 | 批次/工序任务/资源需求/派工/异步排程（快照+漂移守卫）、任务事件消费 |
| product-execution | `product-execution` | 8105 | 任务/资源状态事件（outbox 出站）、现场追溯 |
| product-cloud-common | （库，不部署） | — | 统一错误契约、请求上下文、日志基线（SERVLET 条件装配，WebFlux 网关自动跳过） |
| product-cloud-security | （库，不部署） | — | JWT RS256 验签核心 + JWKS + 服务端本地验签安全链 + @ss 权限（ADR-0003；SERVLET 条件装配，网关只复用纯 Java 验签核心） |
| product-cloud-messaging | （库，不部署） | — | 跨服务事件一致性基础设施（ADR-0004）：版本化 envelope、事务内 Outbox + publisher confirm 中继、eventId 幂等消费、有界重试 + DLX 死信审计、对账/重放/运维审计（JdbcTemplate 访问各服务自有库同名权属表） |

> 命名说明：demand 的 Maven 模块叫 `product-demand-service`（避免与旧业务模块 `product-demand` 坐标冲突），
> Nacos 注册名与配置 Data ID 仍用目标服务名 `product-demand`。

## 网关正式路由（Phase 6 收敛版）

| 路由 | 目标 | 说明 |
| --- | --- | --- |
| /login,/register,/captchaImage,/jwks,/getInfo,/getRouters | product-identity | 登录/匿名端点（permitPaths 由网关 JWT 过滤器处理） |
| /system/user/**,/system/menu/**,/system/dict/** | product-identity | system 管理 |
| /demand/product,/demand/product/** | product-master-data | 产品（baselines §1.2 路径冻结） |
| /pps/product-route,/pps/product-route/** | product-master-data | 工艺路线 |
| /master/calendar,/master/calendar/**；/master/resource/machine,/master/resource/machine/**；/master/resource/fixture,/master/resource/fixture/** | product-master-data | 日历/机台/夹具（Phase 6 补齐的正式路由，夹具为 issue #6 补齐） |
| /pps/batch,/pps/task,/pps/assignment（含 /**） | product-planning | 批次/工序任务/派工 |
| /demand/** | product-demand | 客户/订单/订单行 |
| /execute/event,/execute/event/** | product-execution | 任务事件 |
| /{identity\|master-data\|demand-svc\|planning\|execution}/v3/api-docs** | 各服务（StripPrefix=1） | OpenAPI 文档聚合（springdoc.swagger-ui.urls 引用；匿名可读，baselines §1.3 显式差异） |

- Phase 1 的 StripPrefix 冒烟前缀路由（/identity/**、/planning/** 等）已全部移除，仅保留文档收敛路径。
- `/internal/**` 由 InternalPathDenyFilter 显式拒绝（404 统一错误体），含 URL 解码/矩阵参数变形；
  内部契约端点仅服务间直连与运维脚本可达。

## Sentinel 规则 Nacos 持久化（Phase 6）

- 依赖 `sentinel-datasource-nacos`（SCA BOM 1.8.9）；`spring.cloud.sentinel.datasource.gateway-flow-rules.nacos`：
  data-id=`product-gateway-flow-rules.json`、**group-id=PRODUCT_GATEWAY**（注意属性名是 `group-id`，
  写成 `group` 会静默回退 DEFAULT_GROUP——Phase 6 live 踩坑）、rule-type=`gw-flow`。
- 规则 JSON：`[{"resource":"identity-auth","grade":1,"count":200}, ...]`；Nacos 发布/修改即时热加载；
  未发布配置时 application.yml 的 `product.gateway.sentinel.routes` 属性规则兜底。
- 限流响应：429 + 统一 {msg,code} 错误体 + X-Trace-Id。

## 可观测性（Phase 6 收敛后）

- 健康：`/actuator/health`（liveness/readiness 匿名）；**指标**：`/actuator/prometheus`
  （受 JWT 保护，需管理员 token）；日志：各服务 JSON（ELK 字段）。
- **追踪/OTLP**：micrometer-tracing-otel + `io.opentelemetry:opentelemetry-exporter-otlp`；
  `OTLP_TRACES_ENABLED=true` 打开导出（默认关闭），端点 `OTLP_TRACES_ENDPOINT`
  （compose 提供 jaeger all-in-one：OTLP :4318、UI :16686）。网关入口一次请求的 traceId
  贯穿网关 span（Jaeger）+ 目标服务 JSON 日志；异步事件链以 envelope correlationId（=命令 traceId）
  贯穿三域 outbox（Phase 6 实测 transcript：scratch/phase6/observability_transcript.txt）。

## Nacos namespace / group / Data ID 规范

所有服务 `application.yml` 按下列约定取值（环境变量可覆盖）：

### namespace（环境隔离）

- namespace ID = 环境短名：`dev`（默认，`NACOS_NAMESPACE` 覆盖）、后续 `test` / `staging` / `prod`。
- namespace 必须先在 Nacos 中创建（ID 与名称都用同一个短名），服务启动前需就绪。
- 开发环境经 `compose.dev.yml` 启动 Nacos v3.0.3（`127.0.0.1:8848`，控制台 `127.0.0.1:8090`）。

### 服务发现 group

- 全部服务统一 `PRODUCT_GROUP`（`spring.cloud.nacos.discovery.group`）。
- 服务间调用（Feign/lb://）以 `spring.application.name` 为服务名，不感知 group 差异（同 group 内查找）。

### 配置 group / Data ID

每个服务通过 `spring.config.import` 引入两个远程配置：

| Data ID | group | 用途 |
| --- | --- | --- |
| `product-common.yml` | `PRODUCT_COMMON` | 跨服务共享技术配置（日志级别、暴露端点等公共项） |
| `product-<service>.yml` | `PRODUCT_<SERVICE>` | 本服务专属配置；group 与 Data ID 一一对应，实现服务间配置隔离 |

- `<service>` 取注册名短名：`product-identity.yml` / `product-master-data.yml` / `product-demand.yml` /
  `product-planning.yml` / `product-execution.yml` / `product-gateway.yml`。
- group 命名：大写 + 下划线（`PRODUCT_MASTER_DATA`、`PRODUCT_COMMON`）。
- import 均为 `optional:` 前缀：Nacos 不可用时服务仍可用本地默认值启动（骨架可独立启动的要求）。
- 后续 Sentinel 规则持久化（Phase 2+）沿用同一 namespace，规则 Data ID 按服务名 + 规则类型定义，
  在引入时补充到本节。

### 配置隔离验证方法（Phase 1 已实测）

向 `PRODUCT_COMMON`/`product-common.yml` 写 `product.skeleton.shared-marker`，向
`PRODUCT_<SERVICE>`/`product-<service>.yml` 写 `product.skeleton.config-marker`；
访问 `GET /skeleton/info`，应答中 `sharedMarker` 反映共享配置、`configMarker` 只反映本服务配置
（未发布该 Data ID 的服务保持 `unset`），即证明 group/Data ID 隔离生效。

## 可观测性基线

- **健康检查**：`/actuator/health`（含 liveness `/actuator/health/liveness`、readiness
  `/actuator/health/readiness`，`management.endpoint.health.probes.enabled=true`）。
- **指标**：`/actuator/prometheus`（Prometheus 文本格式，micrometer-registry-prometheus）。
  指标端点受 JWT 保护（需管理员 token）；健康探针匿名。告警规则基线见 `deploy/alerts/product-microservices-alerts.yml`。
- **日志**：各服务 `logback-spring.xml` include `logback/product-cloud-base.xml`；控制台格式与 JSON 文件
  （`app.json.log`/`error.json.log`）字段与现有单体 ELK 管线一致，`service.name` 取 `spring.application.name`。
- **追踪**：micrometer-tracing-otel 桥接，W3C 传播。traceId 一致性链路（Phase 1 实测）：
  - 网关 -> 服务：Spring Cloud Gateway 自带的 Micrometer 观测会把 W3C `traceparent` 传播到代理请求，
    服务端 span 与网关 span 使用同一 traceId（不要在网关层自造 `traceparent`/`X-Trace-Id`，
    会与 SCG 的原生传播冲突、产生双 traceId——Phase 1 踩坑记录）；
  - 服务侧 `RequestContextFilter`（product-cloud-common）：traceId 取值优先级
    `X-Trace-Id` → `traceparent` → 本地生成；当缺少 `traceparent` 时按解析出的 traceId 合成一个
    （00-<trace-id>-<span-id>-01），保证 Micrometer server span、MDC、`X-Trace-Id` 三者同值，
    日志 JSON 的 `traceId` 与 `/skeleton/info` 应答的 `traceId` 一致；
  - 已知边界：网关自身的非代理错误响应（如路由无实例时的 503）尚无统一错误体/X-Trace-Id，
    属 Phase 2 网关错误契约范围。
- **span 导出（Phase 6 已接入）**：`OTLP_TRACES_ENABLED=true` 打开 OTLP 导出
  （端点 `OTLP_TRACES_ENDPOINT`，compose 提供 jaeger all-in-one）；未开启时不配置导出器、不产生导出报错。
  开发期采样率 `management.tracing.sampling.probability=1.0`。

## 认证基线（ADR-0003；Phase 6 收口）

- Identity 签发 RS256 JWT（jjwt 0.12.6），`GET /jwks` 匿名发布公钥（kid=公钥模组 SHA-256 指纹前 16 hex）。
  私钥经 `IDENTITY_JWT_PRIVATE_KEY`（PEM/PKCS#8）注入；未注入时生成临时开发密钥（重启后 token 失效）。
  `IDENTITY_JWT_PREVIOUS_PUBLIC_KEY` 发布轮换窗口公钥，新旧 kid 并存，仅当前密钥签名。
  **轮换演练已实测**（scratch/phase6/jwt_rotation_transcript.txt）：窗口内旧 token 可验签、新登录用新 kid；
  窗口关闭（重启 Identity+刷新网关 JWKS 缓存）后旧 token 401。生产注入方式与撤销语义见
  `docs/微服务运维手册.md` §5。
- 两层校验：网关 `JwtAuthGlobalFilter` 前置验签（permitPaths 对照单体 SecurityConfig），服务端
  `JwtAuthenticationFilter` 本地验签重建 SecurityContext；`@PreAuthorize("@ss.hasPermi(...)")` 与单体同语义。
- **ops 端点管理员门禁（Phase 6 决策）**：planning/demand OpsController 与 execution `/ops/*` 运维端点
  追加 `@PreAuthorize("@ss.hasPermi('*:*:*')")`（重放/人工改写等同生产操作，最小暴露）；服务身份令牌
  permissions 为空集不可调用；失败语义与单体权限契约一致（HTTP 200 + code 403）。契约端点保持 token 语义不变。
- 防伪：服务只信任验签 token，不读明文内部头；网关入口强制剥离 X-User-Id/X-User-Name/X-User-Account/
  X-User-Permissions/X-Internal-Client；直连服务端口必须携带有效签名 token（`product.security.enabled` 默认开启，
  离线单测显式关闭；网关已排除 Boot 默认 Reactive Security，避免默认链抢先 401）。
- 错误体：401（网关与服务）与 404/503（网关非代理错误）、429（Sentinel 限流）均为统一 {msg,code} JSON + X-Trace-Id；
  401 文案与单体 AuthenticationEntryPointImpl 逐字节一致。
- 身份库：identity_db（独立 database）+ 账号 identity_svc（仅 DML 权限）；初始化脚本
  `product-identity/src/main/resources/db/init/identity_schema.sql` 可重复执行（重复执行=重置为种子状态）。
- 缓存：identity 沿用 Redis，key 前缀 `identity:`（`identity:captcha_codes:` 等，ADR-0005 §4 命名空间隔离）。

## 统一错误契约与请求上下文（product-cloud-common）

- 响应信封 `{code, msg, data}` 与现有单体逐字段兼容（`AjaxResult`/`ApiStatus`）；业务错误仍是 HTTP 200 + code，
  权限失败 code=403（baselines.md §1.1）。
- `ServiceException` + `GlobalServiceExceptionHandler`：异常映射与单体 `GlobalExceptionHandler` 语义一致；
  SQL/MyBatis 友好映射待 Phase 2+ 引入数据访问时补齐。
- `RequestContextFilter`：MDC 注入 traceId/requestId/clientIp/httpMethod/requestUri，回写
  `X-Trace-Id`/`X-Request-Id` 响应头，请求结束清理 MDC。

## 本地启动与骨架验证

```bash
# 1. 基础设施（Nacos + RabbitMQ + MySQL；.env 参考 .env.example）
docker compose -f compose.dev.yml up -d nacos rabbitmq mysql

# 2. 创建 dev namespace（ID 与名称均为 dev）
curl -s -X POST "http://127.0.0.1:8848/nacos/v1/console/namespaces" \
  -d "customNamespaceId=dev&namespaceName=dev"

# 3. 发布配置隔离冒烟配置
curl -s -X POST "http://127.0.0.1:8848/nacos/v1/cs/configs" \
  -d "tenant=dev&dataId=product-common.yml&group=PRODUCT_COMMON" \
  --data-urlencode "content=product:
  skeleton:
    shared-marker: from-nacos-common"
curl -s -X POST "http://127.0.0.1:8848/nacos/v1/cs/configs" \
  -d "tenant=dev&dataId=product-identity.yml&group=PRODUCT_IDENTITY" \
  --data-urlencode "content=product:
  skeleton:
    config-marker: from-nacos-identity"

# 4. 构建 + 启动（JDK 17）
mvn -pl product-services -am -DskipTests package
java -jar product-services/product-identity/target/product-identity-*.jar &   # 其余服务同理
java -jar product-services/product-gateway/target/product-gateway-*.jar &

# 5. 断言
curl -s http://127.0.0.1:8080/identity/skeleton/info
#   -> {"service":"product-identity",...,"configMarker":"from-nacos-identity","sharedMarker":"from-nacos-common",...}
#   且 body 的 traceId == 响应头 X-Trace-Id（网关注入 + 服务透传一致）
curl -s "http://127.0.0.1:8848/nacos/v1/ns/service/list?pageNo=1&pageSize=20&namespaceId=dev"
#   -> doms 含全部 product-* 服务
curl -s http://127.0.0.1:8101/actuator/health   # -> UP；/actuator/prometheus 输出指标
```

## 事件一致性基线（Phase 5，ADR-0004）

product-cloud-messaging 在 `product.messaging.enabled=true` 时自动装配；各服务自有库内
同名权属表 `event_outbox` / `consumed_event` / `dead_letter_audit` / `ops_audit`
（服务自建，非单体基线表，根 schema.sql 零改动，见各服务 `db/init/*_schema.sql`）。

### 拓扑（冻结命名，ADR-0004 §5；声明由消费方配置驱动，双侧幂等）

| exchange（direct/durable） | 生产者 | routing key（= eventType） | queue（durable） | 消费者 |
| --- | --- | --- | --- | --- |
| `execution.events` | product-execution | `task.status.changed` | `product-planning.execution` | product-planning（2026-09-16 起 eventType 含 EXCEPTION[KD1 增量，目标态复用 PAUSED]；payload 增加可选 reasonCode，只加不改） |
| `execution.events` | product-execution | `resource.status.changed` | `product-planning.execution` | product-planning（2026-09-16 KD3 起消费回写 master-data 权威资源状态——`ResourceStatusUpdateApi`，fail-closed 不 ack；原仅记录/告警。KD2/R4 起回写成功后按触发集合自动发起全量重排：`toStatus ∈ {DOWN, AVAILABLE}` → Redis pending 标记（`planning:reschedule:pending`，TTL 30min）+ `scheduleAllAsync` 提交互斥去抖，互斥拒绝标记保留、由超时清扫节拍空闲排空补跑；MAINTENANCE/OFFSHIFT/BUSY 只回写不触发；触发动作不使消费失败） |
| `planning.events` | product-planning | `batch.progress.changed` | `product-demand.planning` | product-demand |
| `demand.events` | product-demand | `order_line.progress.changed`（预留，无消费方） | — | — |

死信：每 queue 配 `x-dead-letter-exchange={queue}.dlx` / `routing-key={queue}.dlq`；
DLQ `{queue}.dlq` 由消费方死信审计监听器落库 `dead_letter_audit` 后 ack（审计未落库不 ack）。

### envelope v1（契约冻结；演进策略：只加不改）

`{eventId(UUID,幂等键), eventType(=routing key), version=1, occurredAt(ISO-8601 带时区),
producer, aggregateId, correlationId(透传命令 traceId), payload}`。新增可选 payload 字段
不升版本；字段语义/类型变更或删除必须升 version 并新增 eventType（或 version 分支消费）。

### 可靠投递 / 可靠消费

- 生产：业务行 + `event_outbox` 行同一本地事务；`OutboxRelay` 轮询投递（**原始 JSON 字节
  直接构造 AMQP Message**——勿改回 `convertAndSend(byte[])`，Jackson 转换器会把 byte[]
  Base64 成字符串，Phase 5 live 踩坑），publisher confirm 成功才标 PUBLISHED；失败有界退避
  重试（默认 8 次，1s×2^n 封顶 5min），超限 HALTED 待人工重放。
- 消费：容器逐条成功后确认；业务失败经有界重试（默认 3 次，0.5s×2^n）后拒绝进 DLX；
  `eventId` 去重（consumed_event）+ 同聚合 `occurredAt` 单调性守卫（过期事件记 STALE 丢弃）。

### 对账 / 补偿 / 人工重放

- 各服务内置本域对账（planning：batch=aggregate(tasks)；demand：line=aggregate(投影)、
  order=aggregate(lines)），漂移写 `ops_audit`（RECON_DRIFT）并自动修复（RECON_HEAL）。
- 跨域对账/补偿/重放工具：任务 scratch `phase5/recon.py`（report / heal batch|line|order /
  heal allocation / replay outbox|dlq；经服务内部 ops 端点执行，全部动作服务端 ops_audit 留痕：
  `/internal/{planning|demand}/ops/*`、`/internal/execution/ops/*`；网关对 /internal/** 显式拒绝）。

## 与单体的显式行为差异（issue 修复决议，2026-09-15）

以下差异均为远程 issue 提出的目标态修复（单体保持冻结不改动；API 响应除注明外逐字节兼容）：

| Issue | 差异 | 说明 |
|-------|------|------|
| #5 | 服务内「路由已匹配但端点缺失」由 200+code 500 改为 200+code 404「请求路径不存在」 | `GlobalServiceExceptionHandler` 映射 `NoResourceFoundException`/`NoHandlerFoundException`；与网关 404 同文案，均带 X-Trace-Id。避免调用方误重试与 5xx 错误率告警误报 |
| #1 | `/system/user/{userId}` 增加纯数字约束 | 用户 CRUD 属冻结范围（与单体现状一致），字面路径（如 /list）不再被 `/{userId}` 吞掉返回 500 类型不匹配，改落入统一 404 |
| #2 | 字典缓存读侧容忍未知字段 | `SysDictData.default`（手写 getter 与 @Data 并存所致）序列化写出后缓存读回失败；`RedisConfig.cacheObjectMapper` 关闭 FAIL_ON_UNKNOWN_PROPERTIES，缓存不再自我污染。HTTP 响应 mapper 独立、契约不变 |
| #4 | `/execute/event` 直录语义与命令链对齐 | taskId 非法/不存在拒绝（存在性经 planning 只读契约 fail-closed）；eventTime 缺省由服务端补当前时间。批量导入路径（batchInsertTaskEvent）语义不变 |
| #3 | OpenAPI servers 收敛为相对路径 `/` | 各服务与网关的聚合文档不再泄露实例内网 IP + 直连端口（springdoc 不再按请求解析 server url） |

## LOWEST_COST 综合成本模型（2026-09-16 成本模型精度提升）

`LOWEST_COST` 排程策略的机台选择比较器主键为加权综合成本（仅此策略消费；其余三策略、
平局键 `plannedEnd → plannedStart → machineId`、`QUEUE_SAME_MOLD_FIRST` 前置规则、
任务级排序与时间计算均不受影响）：

```
compositeCost = setup-weight×换型时间(分钟)
              + changeover-count-penalty×换模次数
              + cross-shift-penalty×跨班次次数
              + energy-weight×能耗（预留恒 0）
```

### 因子口径（全部从现有数据派生，零 DDL）

| 因子 | 口径 | 数据源 |
|------|------|--------|
| 换型时间 | 任务声明 `changeoverTimeMin` → 资源需求 `changeoverTimeMin` → 机台 `defaultSetupTimeMin` 三级取值；换型触发时优先取 ChangeoverCalculator 结果 | 现有 `estimateSetupCost`，未修改 |
| 换模次数 | MachineLastAssignment 链（DB 最近派工预加载 + 本次运行内逐任务覆盖，单槽快照）有历史记 1；本次选择再触发换模（setup 类任务且模具不同，与换型时间判定同口径）再计 1；无历史 → 0 | `ResourceRuntimeContext.machineLastAssignmentMap` |
| 跨班次次数 | 候选窗口 `[plannedStart, plannedEnd)` 内严格包含的班次边界数（每工作日 `shiftStart`/`shiftEnd` 各一；恰在窗口端点不计）；全在同一班次内/无日历/缺班次字段 → 0 | 所选机台日历的班次结构 |
| 能耗 | 恒 0（KD1 预留槽位：master-data 尚无能耗字段，权重存在但不影响结果；接入后无需再改公式） | — |

### 权重配置（重启生效；0=关闭该因子，负值启动失败）

```yaml
product:
  pps:
    schedule:
      cost-model:
        setup-weight: 1                 # 分钟等效成本/分钟；默认 1 与历史行为等价
        changeover-count-penalty: 30    # 分钟等效成本/次；一次额外换模 ≈ 30 分钟成本
        cross-shift-penalty: 60         # 分钟等效成本/次；一次跨班 ≈ 60 分钟成本
        energy-weight: 0                # KD1 预留；能耗因子接入前恒 0
```

- 环境变量覆盖：`PRODUCT_PPS_SCHEDULE_COST_MODEL_SETUP_WEIGHT` /
  `_CHANGEOVER_COUNT_PENALTY` / `_CROSS_SHIFT_PENALTY` / `_ENERGY_WEIGHT`。
- 配置类：`com.product.planning.config.ProductCostModelProperties`
  （负值在绑定校验时抛出，服务启动失败，不允许静默取 0）。
- 默认权重（1/30/60/0）下，无换模历史且窗口不跨班的候选 `compositeCost == setupCostMin`，
  与升级前行为可对照；权重全 0 即回滚到「仅换型时间」语义（配置级回滚）。
- 集成验证：`product-integration-tests` 套件 `LowestCostCompositeCostIT`
  （换模历史驱动的选择反转 + 默认配置不回归）。

