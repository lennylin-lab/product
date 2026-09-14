# Implementation Plan

> 本文是本任务进入 `in_progress` 后的实施计划。任务创建与规划审核会话不执行代码变更，后续会话按以下阶段实施、验证和审核。

## Phase 0: Architecture Gate

- [x] 根据官方兼容矩阵选定 Spring Boot、Spring Cloud、Spring Cloud Alibaba 与 JDK 组合，并用空白最小应用验证 Nacos Discovery/Config、Gateway、Feign、Sentinel 和 Spring AMQP/RabbitMQ 可启动。
- [x] 建立 Architecture Decision Records：服务边界、JWT/JWK、事件一致性、数据库隔离、版本基线。
- [x] 冻结外部 API 基线、数据库基线和核心 E2E 用例。
- Validation: BOM 依赖树无冲突；最小应用启动测试通过；ADR 和契约基线评审通过。
- Rollback point: 不修改业务模块；版本组合不兼容时调整基线后重测。

### Phase 0 执行记录（2026-09-14）

- 选定基线：JDK 17 / Spring Boot 3.5.16 / Spring Cloud 2025.0.3 / Spring Cloud Alibaba 2025.0.0.0 / Nacos Server 3.0.x（详见 `adr/0001-version-baseline.md`）。依据：SCA 官方分支对照表（2025.0.x ↔ SC 2025.0.x ↔ Boot 3.5.x，JDK 17+）与 Maven Central 最新补丁。
- 实际验证（本机真实执行）：最小应用 `scratch/sca-verify/` 三模块 BUILD SUCCESS；verbose 依赖树 0 冲突；Nacos v3.0.3 + RabbitMQ 3.13 容器实机启动，三服务注册成功、Nacos Config 远程属性生效、Feign 经注册中心调用成功、Sentinel QPS 流控生效、AMQP 收发成功、Gateway `lb://` 路由成功（步骤与断言见 `scratch/sca-verify/README.md`）。
- 产出：`adr/0001`~`0005`、`baselines.md`（API/数据库/E2E 基线）。
- 遗留/待评审：① ADR 与基线文档的"评审通过"尚待用户/主会话确认；② 项目 Boot 补丁版本 3.5.0 → 3.5.16 的升级动作留待 Phase 1 父 POM 重组；③ `/logout` 与 `/register` 现状未实现，已在 baselines.md 冻结为"不存在"。

## Phase 1: Platform Skeleton

- [x] 重组父 POM 与公共依赖管理，创建 Gateway 和五个业务服务启动骨架（ADR-0002 边界：gateway + identity/master-data/demand/planning/execution）。
- [x] 建立 Nacos namespace/group/Data ID 规范和本地 Compose 基础设施。
- [x] 建立统一错误契约、请求上下文、OpenAPI、健康检查、日志、指标和追踪基线。
- [x] 建立 CI 构建、镜像和服务级测试框架。
- Validation: 所有空骨架独立启动、注册、健康检查、经 Gateway 路由；配置隔离和 observability smoke test 通过。
- Rollback point: 保留原模块与启动方式，骨架未通过不得迁移业务。

### Phase 1 执行记录（2026-09-14）

**范围**：仅平台骨架，未迁移任何业务代码（Phase 2+ 内容零改动）；根 POM 变更仅限版本基线与 `<module>product-services</module>`。

**产出**：
- `product-services/`：聚合 POM + `product-cloud-common`（统一错误契约 AjaxResult/ApiStatus/ServiceException、`GlobalServiceExceptionHandler`、`RequestContextFilter`、`SkeletonController`、自动装配与共享日志基线 `logback/product-cloud-base.xml`）+ `product-gateway` + 5 个服务骨架（identity / master-data / demand-service / planning / execution，各含 Application、application.yml、logback-spring.xml、Dockerfile、离线冒烟测试）。demand 的 Maven 模块名为 `product-demand-service`（避开旧业务模块坐标），Nacos 注册名仍为 `product-demand`。
- 规范文档：`product-services/README.md`（namespace/group/Data ID 规范、可观测性基线、本地启动与验证步骤）。
- CI：`.github/workflows/build.yml`（GitHub Actions：temurin 17 + Maven 缓存 + `mvn -B -ntp package`，覆盖单体与微服务全部模块的构建与单测；仓库远端为 GitHub，故选 GH Actions 而非本地脚本）。
- Compose：`compose.dev.yml` 新增 nacos(v3.0.3)/rabbitmq(3.13-management)；`.env.example` 增加对应变量。

**验证结果（本机实测，JDK temurin 17.0.20 / Maven 3.9.16）**：
1. 根构建（回滚点）：`mvn -DskipTests compile` 23/23 模块 BUILD SUCCESS；`product-server` 依赖树解析 `spring-boot:3.5.16`（3.5.0→3.5.16 补丁升级后单体系体持续可构建）。
2. 服务测试：`product-services` 下 `mvn test` 8/8 模块 BUILD SUCCESS，36 个用例全部通过（网关 2、公共库 19、各服务 15；测试全部离线，禁用 Nacos 注册/配置）。
3. 实机启动：Nacos v3.0.3 + RabbitMQ 3.13（compose，均 healthy）+ MySQL 8.4（compose 既有实例）之上，6 个 fat-jar 真实 JVM 启动，日志均出现 `NacosServiceRegistry: register finished`。
4. 注册：Nacos `namespaceId=dev&groupName=PRODUCT_GROUP` 服务列表 6/6（product-gateway/identity/master-data/demand/planning/execution）。
5. 健康检查：6/6 `/actuator/health` UP，readiness probe UP。
6. 网关路由：`/identity/**`、`/master-data/**`、`/demand-svc/**`、`/planning/**`、`/execution/**` 五条 `lb://` 路由全部路由到对应实例并应答 `/skeleton/info`。
7. 配置隔离：`PRODUCT_COMMON/product-common.yml`（shared-marker）对所有服务生效；`PRODUCT_IDENTITY/product-identity.yml`（config-marker）仅 identity 生效，其余服务保持 local-default —— namespace(dev)/group/Data ID 三级隔离成立。
8. 可观测性冒烟：`/actuator/prometheus` 输出 54 组指标（含 http_server_requests）；经网关请求 404，服务 JSON 日志（ELK 字段）记录的 traceId == 响应头 `X-Trace-Id` == 服务端 MDC/span traceId，日志-链路关联成立。
9. 清理：6 个 JVM 全部停止、端口释放；nacos/rabbitmq 容器停止并移除；用户既有 product-mysql 容器与单体运行进程未受影响。

**审计中发现并修复的问题**（前次中断遗留的骨架缺陷）：
- `product-cloud-common` 缺 lombok 依赖（编译失败）→ 补 provided 依赖。
- `MasterDataApplication`/`PlanningApplication` javadoc 中 `product_*/route_*`、`task_*/` 等通配符包含 `*/`，提前终止注释导致编译失败 → 改写为表名枚举。
- `SkeletonController` 无类级 `@RestController`，Spring MVC 不识别为处理器（MockMvc 发现不了）→ 补注解。
- `FilterRegistrationBean` 默认名 `requestContextFilter` 与 Boot 3.5 `WebMvcAutoConfiguration` 自带的同名过滤器冲突，真实 Tomcat 启动即失败（MockMvc 发现不了）→ 更名 `productRequestContextFilter`。
- `RequestContextFilter` 仅写 MDC 不合成 `traceparent`，Micrometer server span 会用新 traceId 覆盖 MDC，导致 `X-Trace-Id` 与日志/链路分叉 → 缺失时按解析出的 traceId 合成 traceparent（服务侧与网关原生传播同规则）。
- 网关侧初版自造 `traceparent`/`X-Trace-Id` 的 `GatewayTraceFilter` 与 SCG 原生 Micrometer 传播冲突产生双 traceId → 删除，改用 SCG 原生传播（踩坑记录见 product-services/README.md）。
- compose 的 Nacos healthcheck 用 v1 readiness 端点（v3 已 410 Gone）→ 改为容器内 `:8080/v3/console/health/readiness`。
- 3 个单测断言/构造缺陷（类型不匹配消息期望值、MethodArgumentNotValidException 构造、UUID 断言字符集）→ 按单体契约修正。

**环境备注**：本机默认 JDK 17.0.2（mise）存在 cgroup v2 下的 `ProcessorMetrics` 崩溃 bug（旧补丁版已知问题），actuator 应用无法启动；实测改用 temurin 17.0.20（17 线最新补丁，符合 ADR-0001 补丁策略）。CI 使用 temurin 17（latest patch）不受影响。

**遗留问题（不阻塞 Phase 2）**：
- 网关非代理错误响应（如无实例 503）尚无统一错误体与 `X-Trace-Id`，随 Phase 2 网关错误契约补齐。
- Nacos v3 的 v1/v2 console API 已 410：namespace 建立需用 v3 console API（需先初始化 admin 用户）或控制台；`/nacos/v1/cs/configs` 与 `/v1/ns/*` 读写仍可用（Phase 1 实测）。后续版本升级时关注 compatibility 开关。
- span 导出器（OTLP collector）未接，按计划 Phase 6 接入；Sentinel 规则持久化、OpenAPI 网关聚合的正式路由随 Phase 2/3 落地。
- CI 尚未在远端 GitHub 实际触发验证（本地无法验证 runner 行为），推送后需确认首次运行。

## Phase 2: Identity And Gateway

- [x] 将认证、用户、角色、菜单、字典归入 Identity，拆出独立 schema 和缓存命名空间。
- [x] 将 JWT 改为可供 Gateway/服务本地验证的签名与密钥发布机制，建立内部用户上下文防伪约束。
- [x] 配置 Gateway 路由、权限前置、CORS、Sentinel 和统一错误响应。
- Validation: 登录、验证码、Token 刷新/过期、菜单权限、匿名接口、越权和限流契约测试通过。
- Rollback point: Identity 数据迁移脚本可重复；失败恢复身份库备份和原认证构建。

### Phase 2 执行记录（2026-09-14）

**范围**：Identity 从骨架升级为完整服务（对照单体 SysLoginController/SecurityConfig/PermissionService/sys_* 表，
对外请求/响应形状与单体一致）；Gateway 补齐权限前置、CORS、Sentinel 限流与统一错误响应；单体代码零改动（根 pom 未动）。

**产出**：
- `product-services/product-cloud-security`（新库模块）：JWT RS256 验签核心（jjwt 0.9.1 → 0.12.6，ADR-0003）——
  `JwtSigner`/`JwtVerifier`（仅接受 RS256，拒绝 alg 混淆）/`Jwks`（RFC 7517 编码，kid=模组 SHA-256 指纹前 16 hex，确定性）/
  `RsaPem`（PKCS#8/X.509）/`JwksKeyHolder`（按 kid 缓存 + 未知 kid 自动刷新一次，轮换窗口）；SERVLET 条件装配的
  `JwtAuthenticationFilter`（本地验签重建 SecurityContext + MDC userId/username）、`ProductAuthenticationEntryPoint`
  （与单体 401 错误体逐字节一致：HTTP 200 + `{"msg":"请求访问：<uri>，认证失败，无法访问系统资源","code":401}`）、
  `PermissionService`（@ss，hasPermi/hasAnyPermi/hasRole 语义与单体一致；token 不携带角色 → hasRole 恒 false，行为冻结）、
  `ProductSecurityUtils`。claims 与单体同名同义（sub/userId/permissions/loginTime/expireTime/ipaddr/loginLocation/browser/os/userName/avatar）
  + 标准 iss/iat/exp/jti + header kid；exp 与 expireTime 同值（单体自包含 token 实际未强制过期，按 ADR-0003 收敛为强制过期）。
- `product-services/product-identity`：从单体移植实体 7/DTO/VO/Mapper 7(+XML 3)/Service/Controller（仅移植单体**激活**端点：
  SysUserController 的 CRUD 在单体中被注释，identity 仅保留 getInfo；/logout、/register 冻结为不存在）；
  登录链路（SysLoginService/SysPasswordService/AuthenticationContextHolder/UserDetailsServiceImpl，异常与 i18n 消息逐一对应）；
  `JwtKeyManager`（私钥经 `IDENTITY_JWT_PRIVATE_KEY` 注入，缺省生成临时开发密钥；`IDENTITY_JWT_PREVIOUS_PUBLIC_KEY` 发布轮换窗口公钥）+
  `JwtTokenService`（单体 JwtUtils.createToken 的 RS256 化）+ `GET /jwks`（匿名）；Redis `RedisCache`+`RedisConfig`（单体同款 Jackson 序列化）
  + `CacheConstants` 全部 key 加 `identity:` 前缀；字典缓存 DictUtils；`ExcelExporter`（字典类型/数据导出，读同一 @Excel 注解；
  xlsx 二进制不逐字节一致——文件本身含时间戳，单体两次导出亦不同，响应头一致）；`ApplicationConfig`（Long→String、
  LocalDateTime "yyyy-MM-dd HH:mm:ss"，契约关键）、`MybatisPlusConfig`（分页 maxLimit=100/overflow=true + MetaObjectHandler）、
  `RedisConfig`、`IdentitySecurityConfig`（DaoAuthenticationProvider+BCrypt）。
- `product-services/product-gateway`：identity 正式路由（Path=/login,/register,/captchaImage,/jwks,/getInfo,/getRouters 与
  /system/user|menu|dict/**，不剥离前缀；Phase 1 前缀路由保留给未迁移服务）；`JwtAuthGlobalFilter`（permitAll 对照单体
  SecurityConfig：/login,/register,/captchaImage + /jwks/swagger/actuator；其余必须有效签名 token，验签在 boundedElastic 执行）；
  `InternalHeaderSanitizerFilter`（入口剥离 X-User-Id/X-User-Name/X-User-Account/X-User-Permissions/X-Internal-Client，且不注入任何明文身份头）；
  `GatewayErrorWebExceptionHandler` + `GatewayErrorBodies`（非代理错误统一 {msg,code} 信封 + X-Trace-Id：404/503/401/429）；
  Sentinel 网关限流（GatewayFlowRule 按路由 QPS 装载，`product.gateway.sentinel.routes.*`，限流响应统一错误体）；
  全局 CORS（对照单体 CorsFilter：originPattern */header */method */maxAge 1800）；排除 Boot 默认 Reactive Security（避免默认链 401 干扰）。
- 其余 4 个服务骨架（master-data / demand-service / planning / execution）挂上 product-cloud-security
  （`product.security.enabled` 默认开启——直连端口必须有效签名 token）。**trellis-check 修正**：初版骨架 pom 依赖
  编辑被脚本静默跳过（未生效却误记"已挂接"），检查门发现后已实际写入 4 个 pom，并在每个骨架新增
  `*DirectPortSecurityTest`（MockMvc、安全链开启、断言 /skeleton/info 无 token 返回单体逐字节 401 错误体）作为离线证明；
  对应 `*ApplicationTest` 关闭安全链时同时排除 Boot 默认 servlet 安全链（cloud-security 引入 spring-security-web/config
  后 `@ConditionalOnDefaultWebSecurity` 兜底链会抢先 401）。compose.dev.yml 未改（redis 6380 已存在）。
- SQL：`product-identity/src/main/resources/db/init/identity_schema.sql`（ADR-0005：独立 database `identity_db` +
  独立最小权限账号 identity_svc，仅 identity_db 的 DML 权限；sys_* 7 表+种子从根 schema.sql 原样提取；可重复执行）。

**缓存决策（如实记录）**：单体已使用 Redis（验证码/密码重试计数/字典缓存，product-cache RedisConfig：String key + Jackson value 序列化），
故 identity 沿用 Redis 而非进程内缓存，与 ADR-0005 §4 一致：同一 Redis 实例，`identity:` key 前缀命名空间隔离
（`identity:captcha_codes:`、`identity:pwd_err_cnt:`、`identity:sys_dict:`），缓存不作为跨服务事实源。

**schema 隔离（ADR-0005）**：`identity_db`（共享 MySQL 实例）+ 账号 `identity_svc`（CREATE USER IF NOT EXISTS + ALTER USER + GRANT SELECT/INSERT/UPDATE/DELETE 仅限 identity_db）。
脚本幂等性实测：连续执行 2 次，sys_user/role/menu/dict_data 计数均为 1/2/44/5，账号唯一（重复执行=重置回已知初始状态，与根 schema.sql 语义一致）。
实测账号隔离：identity_svc 查 product.sys_user → ERROR 1142 (SELECT command denied)。

**验证结果（本机实测，temurin 17.0.20+8）**：
1. 离线（trellis-check 修正后重计，`mvn -B -ntp clean test` 全量输出为准）：product-services 9/9 模块 BUILD SUCCESS，
   **80 个用例全绿**：cloud-common 19、cloud-security 11、gateway 9、identity 25（18 基线 + 7 条 SQL 映射新增）、
   master-data/demand-service/planning/execution 各 4（3 骨架冒烟 + 1 直连防伪 401 契约）；
   根工程 24/24 模块 `mvn -DskipTests compile` 通过（单体重建正常，回滚点成立）。
   （初版执行记录曾写"84 用例、identity 16、其余骨架 29"，模块拆分与实际不符，已按 clean run 输出更正。）
2. 实机：nacos v3.0.3 + rabbitmq 3.13（compose）+ 既有 mysql 8.4/redis 7.4 之上，identity(8101)/gateway(8080) 真实 JVM 启动，
   PRODUCT_GROUP 注册 2/2，健康检查 UP；Nacos v3 namespace 用 admin API 创建：
   `POST /nacos/v3/admin/core/namespace -H "serverIdentity: <NACOS_AUTH_IDENTITY_VALUE>" -d "namespaceId=dev&namespaceName=dev"`
   （v1 console API 已 410；identity header 即 compose 的 NACOS_AUTH_IDENTITY_KEY/VALUE，可免控制台 admin 初始化——解决 Phase 1 遗留）。
3. 真实登录链路：`GET /captchaImage`（匿名）→ Redis 取 `identity:captcha_codes:<uuid>` → `POST /login`（admin/admin123）→ RS256 token
   （header kid、claims iss=product-identity/sub/userId/permissions=["*:*:*"]/loginTime/expireTime）→ `GET /getInfo`（roles/permissions/user）、
   `GET /getRouters`（菜单树）、`/system/menu/list`、`/system/dict/type/list`（total 为字符串——单体 Long→String 契约）全通。
4. 防伪与鉴权：无 token/伪造 token/篡改 token 访问受保护端点（经网关与直连 8101）均返回单体逐字节 401 错误体；
   伪造 X-User-Id: 1 + X-User-Name: admin（无 token）仍 401（内部头被剥离且服务只认验签）；有效 token 直连 8101 → 200（服务端本地验签成立）。
5. 越权：普通用户 ptester（common 角色）登录成功，`/system/menu/list`、`/system/dict/type/list` 均返回
   `{"msg":"没有权限，请联系管理员授权","code":403}`（token 内 permissions 为空 → @ss.hasPermi=false → AccessDenied，与单体同因同果）。
6. 网关错误契约（Phase 1 遗留项）：未路由 404、无实例 503、未认证 401、Sentinel 429 全部返回统一 {msg,code} 错误体 + X-Trace-Id。
7. Sentinel：`GATEWAY_IDENTITY_AUTH_QPS=2` 启动网关，12 连发 /captchaImage → 前 2 个 200、其余 429 + 统一错误体 + X-Trace-Id。
8. CORS：OPTIONS 预检回显 Origin、Access-Control-Allow-Methods 回显请求方法、Max-Age=1800（与单体 CorsFilter 行为一致）。
9. 契约 diff（同账号同权限，scratch/phase2/contract_diff.py，每轮计算 28 项检查：2 账号 × [登录 diff + token 存在性 +
   11 个 GET 端点 diff + 匿名 401 体 diff]）：与**当前源码构建的单体**（8082 端口临时实例；8081 上用户常驻进程为 fc52328 提交前的
   旧构建，/getInfo 的 Long 未走字符串序列化，不能作基线——首轮 diff 曾对旧实例执行、结果作废重跑）admin+ptester 双账号
   逐字段比对（归一化 token/uuid/img/loginDate/loginIp/createTime/updateTime）。**实际执行 2 轮完整 diff，均为 26/28 通过**：
   通过项含登录响应、getInfo、getRouters、菜单/字典分页、treeselect、roleMenuTreeselect、optionselect、profile、user/{id}、
   匿名 401 体（diff=0）；未通过的 2 项同为 `GET /system/dict/data/type/sys_user_sex`——该端点在单体本身即报错（Redis 字典缓存含
   SysDictData 不可反序列化的 "default" 字段，冻结 bug），identity 同样报错，信封与语义一致，仅 msg 内嵌的实体类全限定名与
   列偏移不同（`com.product.domain.entity.SysDictData` vs `com.product.identity.domain.entity.SysDictData`，295 vs 304）——
   属已冻结缺陷的等价复现，内部类名无法跨模块相同。
10. Token 过期：exp=expireTime 由 jjwt 强制校验（含 clockSkew 60s），过期/伪造单测覆盖（过期 token 现场构造依赖私钥，未做实机长跑）；
    Token 刷新保持单体语义（无服务端状态，20 分钟静默刷新仅日志提示，无刷新端点）。

**过程中发现并修正**：
- `GlobalServiceExceptionHandler` 的 NoResourceFoundException 映射（Phase 1 曾定义 404+"请求资源不存在"）与单体不一致
  （单体落 Exception 兜底 → code 500 + "No static resource xxx."）→ 按单体对齐并实测 /register 双侧一致。
- 服务端 `SecurityUtils` 与请求态 `PrincipalUser` 的类型桥接：新增 `UserPrincipalView` 接口（getUserId/getUsername/getPermissions），
  `PrincipalUser` 与 identity 登录期 `LoginPrincipal` 均实现，控制器/工具对两种主体一视同仁。
- 网关需排除 Boot 默认 Reactive Security（ReactiveSecurityAutoConfiguration 等 3 个），否则默认链先于路由生效（全 401 + 自动登录页）。
- maven-jar-plugin 3.5.0 对未变化输入跳过重建：依赖（cloud-common）更新后需 `clean package` 才进 fat-jar（踩坑记录）。

**环境备注（重要）**：清理阶段误将 8081 上用户 IDE 启动的单体进程（62420，启动于 06:44，为 fc52328 提交前的旧构建）匹配了清理 pattern
而终止；已用当前源码在 8081 重新拉起单体（spring-boot:run，JDK 27，SPRING_DATA_REDIS_PASSWORD=123456），captchaImage 实测 200。
注意该实例现在与源码一致（Long→String 生效），前端如依赖旧行为（数字 ID）需知悉——但这正是 HEAD 提交 fc52328 的意图。

**trellis-check 检查项修正（2026-09-14，全部复核通过）**：
1. SysLoginServiceTest 两处断言误用 ServiceException——生产 SysLoginService（单体忠实移植，未改动）抛
   UserPasswordNotMatchException（i18n user.password.not.match="用户不存在/密码错误"）；测试改为断言该异常类型。
2. 骨架安全挂接为虚假记录——4 个 pom 依赖编辑此前被脚本静默跳过；已实际写入并按上述方式离线证明。
3. identity 新增 GlobalSqlExceptionHandler（@Order 最高，独立于 cloud-common 通用 advice——mybatis/spring-tx 类型
   不能进共享库，否则无 DB 骨架在内省 @ExceptionHandler 类字面量时启动失败）：SQLException/DataAccessException/
   PersistenceException/MyBatisSystemException 的错误文案与单体 getSqlErrorMessage 逐字一致，7 条单测镜像单体行为。
4. identity_schema.sql sys_dict_data 恢复 `AUTO_INCREMENT=100`（与根 schema.sql 逐字一致）。
5. .env.example 补充 IDENTITY_DB_HOST/PORT/PASSWORD 与 IDENTITY_JWT_PRIVATE_KEY/PREVIOUS_PUBLIC_KEY（与
   identity application.yml 及 identity_schema.sql 默认值一致，脚本注释恢复成立）。
6. GatewayErrorBodies.envelope 改用 Jackson 序列化（401 msg 内嵌请求 URI 需合法转义；标准错误体仍与单体逐字节一致，
   gateway 既有字节契约测试未弱化、继续通过）。
7. SysLoginController 移植自单体的孤立 `;` 移除。
8. 本记录的用例总数/骨架挂接/diff 记账已按实际执行更正（见上文对应条目）。

**遗留问题（不阻塞 Phase 3）**：
- Sentinel 规则为网关进程内存规则（属性装载）；Nacos 持久化（sentinel-datasource）随 Phase 3+。
- identity 未注入生产 JWT 私钥时使用临时开发密钥（重启后 token 失效）；正式密钥与轮换演练在 Phase 6 统一切换前完成。
- `GET /system/dict/data/type/{type}` 的 "default" 字段反序列化失败为单体冻结 bug（identity 等价复现）；修复属新增功能，需单独评审。
- 8081 单体现在由 spring-boot:run 拉起（非用户 IDE 进程）；主会话可按需从 IDE 重启。

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
