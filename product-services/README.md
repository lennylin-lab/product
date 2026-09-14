# product-services — Spring Cloud Alibaba 微服务骨架（Phase 1）

> 依据：`adr/0001-version-baseline.md`（JDK 17 / Boot 3.5.16 / SC 2025.0.3 / SCA 2025.0.0.0 / Nacos 3.0.x / Sentinel 1.8.9）、
> `adr/0002-service-boundaries.md`（5 服务 + 1 网关）。Phase 1 仅平台骨架，不含业务代码；业务迁移自 Phase 2 开始。

## 模块与端口

| Maven 模块 | `spring.application.name`（= Nacos 注册名） | 端口 | ADR-0002 职责 |
| --- | --- | --- | --- |
| product-gateway | `product-gateway` | 8080 | 唯一入口、路由、CORS、限流、JWT 前置校验、trace 注入 |
| product-identity | `product-identity` | 8101 | 登录、验证码、JWT 签发、用户/角色/菜单/字典/权限（Phase 2 迁移） |
| product-master-data | `product-master-data` | 8102 | 产品/工艺路线/资源/机台/模具/日历/换型规则（Phase 3 迁移） |
| product-demand-service | `product-demand` | 8103 | 客户/订单/订单行及订单生命周期（Phase 3 迁移） |
| product-planning | `product-planning` | 8104 | 批次/工序任务/资源需求/派工/排程（Phase 4 迁移） |
| product-execution | `product-execution` | 8105 | 任务/资源状态事件、现场追溯（Phase 5 迁移） |
| product-cloud-common | （库，不部署） | — | 统一错误契约、请求上下文、日志基线（SERVLET 条件装配，WebFlux 网关自动跳过） |
| product-cloud-security | （库，不部署） | — | JWT RS256 验签核心 + JWKS + 服务端本地验签安全链 + @ss 权限（ADR-0003；SERVLET 条件装配，网关只复用纯 Java 验签核心） |

> 命名说明：demand 的 Maven 模块叫 `product-demand-service`（避免与旧业务模块 `product-demand` 坐标冲突），
> Nacos 注册名与配置 Data ID 仍用目标服务名 `product-demand`。

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
- 骨架阶段不配置 span 导出器（无 OTLP collector，不产生导出报错）；collector 接入在统一切换前（Phase 6）完成。
  开发期采样率 `management.tracing.sampling.probability=1.0`。

## 认证基线（Phase 2，ADR-0003）

- Identity 签发 RS256 JWT（jjwt 0.12.6），`GET /jwks` 匿名发布公钥（kid=公钥模组 SHA-256 指纹前 16 hex）。
  私钥经 `IDENTITY_JWT_PRIVATE_KEY`（PEM/PKCS#8）注入；未注入时生成临时开发密钥（重启后 token 失效）。
  `IDENTITY_JWT_PREVIOUS_PUBLIC_KEY` 发布轮换窗口公钥，新旧 kid 并存，仅当前密钥签名。
- 两层校验：网关 `JwtAuthGlobalFilter` 前置验签（permitAll 对照单体 SecurityConfig），服务端
  `JwtAuthenticationFilter` 本地验签重建 SecurityContext；`@PreAuthorize("@ss.hasPermi(...)")` 与单体同语义。
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
