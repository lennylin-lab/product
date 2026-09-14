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

- [x] 按设计迁移主数据及订单域，消除 `demand -> pps` Java 依赖。
- [x] 建立产品/工艺/资源批量查询契约，定义数据版本字段。
- [x] 拆分 schema、Mapper、Entity 和 API DTO；禁止共享持久化模型。
- Validation: 主数据 CRUD、订单全生命周期、权限、分页/导入导出和跨域引用校验测试通过。
- Rollback point: 每个 schema 独立初始化和校验；迁移失败可清库重建或恢复备份。

### Phase 3 执行记录（2026-09-14，含中断续作审计）

**续作说明**：前一次 trellis-implement 会话因用量中断，树上遗留未提交的部分工作。本次先审计后补齐：
部分工作中 master-data 已完成忠实移植（11 表实体/服务/控制器 + `InternalMasterDataController` +
`master_data_data_version` 版本计数 + 写路径 bump），demand 仅有基础设施（common/core/config/domain 实体/VO +
schema 脚本）；api 契约模块完整。审计发现并修复的部分工作缺陷：① master-data 缺
`common/exception/ServiceException`（多处 import 悬空，编译失败）；② 两服务均缺 `UtilException`、
`core/domain/CurrentUser`、`core/utils/SecurityUtils`（identity 有同类，按包路径移植）；③ 4 个文件
（IMachineService/IResourceService/MachineServiceImpl/ResourceServiceImpl）package 声明仍是旧的
`com.product.masterdata.resource.*` 与物理路径不符（duplicate class）；④ 两服务 pom 缺 lombok（实体
@Data 不生效）；⑤ MachineServiceImpl 缺 MasterDataVersionService import；⑥ gateway 仍是 Phase 1 前缀
占位路由、`.env.example` 缺 MASTER_DATA_DB_*/DEMAND_DB_*。

**产出**：
- `product-services/product-master-data-api`（新契约模块，ADR-0002 决策 1）：`MasterDataBatchQueryApi`
  （@FeignClient name=product-master-data, contextId=masterDataBatchQueryClient, path=/internal/master-data）
  + 8 个纯 Java DTO（无任何 MyBatis/持久化注解，仅依赖 openfeign 注解）。语义：existsProducts（空集合
  拒绝）、products/batch（空=全量，供 Phase 4 快照）、resources/batch（id+type 过滤）；单次 ID 上限
  1000、重复 ID 拒绝。javadoc 记录超时/不重试/fail-closed/透传用户 JWT 契约。
- `product-master-data`：产品归位（原 `/demand/product` 在单体 product-demand 模块，随数据所有权迁入，
  对外路由保持 `/demand/product`）、`/pps/product-route` 保持路径（baselines §1.2）；`/demand/product`+
  `/pps/product-route` 的 Entity/Mapper/XML（MachineMapper.xml）/Service/Controller 忠实移植 + 全部写路径
  同事务 `MasterDataVersionService.bump()`；产品存在性校验改本库查询；`hasBlockingTasksForProduct` 在
  planning 迁移前返回 false（planning_db 为空 ⇒ 行为等价，**Phase 4 必须接线** demand/planning 契约）；
  完整 Mapper 集（含 ProductMoldParamMapper，见下方"单体冻结缺陷"——该 Mapper 的存在恰好修复了单体的
  TableInfoCache 崩溃）。
- `product-demand-service`：Customer/CustomerOrder/OrderLine + CustomerOrderVO/ProductionBatchView 的
  Mapper/XML/Service/Controller 忠实移植（订单全生命周期：NEW↔CONFIRMED 人工流转守卫、IN_PRODUCTION/
  DONE 禁改、check/cancelCheck、release/cancelRelease、订单删除级联删行）；`/export /importTemplate
  /importData` 三资源齐全（ExcelUtil 为单体 core/utils 的服务内拷贝）；orderLine 保留"必须指定存在订单"
  守卫（订单行必须指定所属订单ID / 所属订单不存在: {id}）。与单体的差异（ADR-0005 权属重排，均有
  javadoc）：
  1. **跨域引用校验（新增，Phase 3 验收项）**：写路径（insert/batchInsert/update 且 product_id 非空）
     经 `MasterDataReferenceValidator`→OpenFeign 批量契约校验产品存在；不存在→`产品不存在: productId=..`；
     master-data 不可达/超时/响应非法→`主数据服务不可用/响应异常...`（**fail-closed，无静默默认**，
     Retryer.NEVER_RETRY，connect 2s/read 3s，`FeignForwardAuthConfig` 透传调用方 Authorization）。
     单体同库时代无此校验（接受任意 product_id）——契约 diff 单列为显式差异。
  2. 订单行详情的 `productionBatchList` 恒为空列表（production_batch 归 planning；Phase 4 经契约填充；
     与单体"未拆批"场景响应一致）。
  3. 删除订单行不再级联删 production_batch（跨服务表不可写；Phase 3 planning_db 为空 ⇒ 等价；
     **Phase 4 接线** planning 契约/补偿）。
  4. 单体 release() 中的 `System.out.println` 调试输出未移植（非契约行为）。
- Gateway 正式路由（声明顺序敏感）：`/demand/product**`→master-data、`/pps/product-route**`→master-data
  （均在 `/demand/**`→product-demand 之前）；Phase 1 前缀冒烟路由保留。
- 数据版本字段方案（baselines §2 补充，为 Phase 4 快照）：行级 `version`=该行 update_time epoch 毫秒
  （null 记 0）；路线业务版本沿用单体 product_route.version（"v1"）；信封 `snapshotVersion`=服务权属表
  `master_data_data_version`（scope=MASTER_DATA 单计数器，写事务内 ON DUPLICATE KEY 单调递增）。
  资源扩展表/能力矩阵无时间戳列（单体如此），其变化由 snapshotVersion 承载。
- SQL：`master_data_schema.sql`（11 表逐字节自根 schema.sql + master_data_data_version 服务权属表 +
  master_data_svc 账号）；`demand_schema.sql`（3 表逐字节 + demand_svc）。两脚本注释已声明权属边界。

**验证结果（本机实测，temurin 17.0.20+8 / Maven 3.9.16）**：
1. 根构建（回滚点）：`mvn -B -ntp -DskipTests compile` **25/25 模块** BUILD SUCCESS（24+新增 api 模块；
   单体 0 改动，git status 仅 product-services/** 与 .env.example）。
2. 离线测试：`product-services` 下 `mvn -B -ntp clean test` **10/10 模块 BUILD SUCCESS，91 用例全绿**
   （cloud-common 19、cloud-security 11、gateway 9、identity 25、master-data 4、demand-service 15
   【含 MasterDataReferenceValidatorTest 6 + OrderLineServiceImplTest 5 的新增跨域校验单测】、planning 4、
   execution 4；日志存 `scratch/phase3/phase3_clean_test.log`）。测试修正：两服务 ApplicationTest 按
   identity 方式排除 DataSource/MyBatis-Plus 自动装配并 @MockitoBean 占位 Mapper（否则 DB health
   indicator 离线 503）。
3. Schema：两脚本连续执行 2 次通过（重置语义）；AUTO_INCREMENT 起点 customer/order_line/calendar/product
   均保持 100；master_data_db 12 表（11 基线+版本表）、demand_db 3 表；隔离实测：master_data_svc 查
   product.sys_user → ERROR 1142；demand_svc 查 identity_db.sys_user → ERROR 1142、CREATE TABLE → 1142
   （DML-only 成立）；本服务库 SELECT 正常。
4. 实机（nacos v3.0.3+rabbitmq compose、既有 mysql 33066/redis 6380；identity 8101 / master-data 8102 /
   demand 8103 / gateway 8080，均注册 PRODUCT_GROUP、健康 UP）：
   - 主数据：建产品（带 moldParams+3 工序启用路线）→ getInfo 聚合返回、分页 list（total/rows、
     Long→String）、`/pps/product-route` 建 v2 非启用路线→activate→旧路线自动停用（单活跃约束）→
     product/{id}/active、list desc 正确；再建第二条启用路线被 `uk_product_route_active` 拦截（数据已存在，
     请检查重复数据——与单体同款 SQL 映射错误体）。
   - Excel：产品 export/importTemplate 200（xlsx）；客户 importTemplate→openpyxl 注入行→importData
     `导入成功1条信息！`→list 可见→export 200。产品 importData 报"产品必须指定至少一条模具参数"与单体
     一致（模具参数无 @Excel 列，单体往返同样必败，冻结缺陷，见下）。
   - 订单全生命周期：建客户→建订单（IdWorker 主键）→订单行（valid product 经 Feign 校验通过）→
     check→release→cancelRelease；订单 getInfo 内嵌 orderLineList；行 getInfo 内嵌
     productionBatchList:[]；DELETE 订单级联删行（DB 验证 0 残留）；无 orderId 建行→"订单行必须指定所属
     订单ID"；未确认订单 release→"订单未确认"。
   - 跨域校验：product=999999 建行→`产品不存在: productId=999999`（Feign 经 Nacos 直连 8102）；
     杀掉 master-data 后建行→`主数据服务不可用，无法校验产品引用，请稍后重试`（~30ms 快速失败，
     无静默放行）；重启后恢复。
   - 内部契约端点：`POST /internal/master-data/products/exists|products/batch` 直连带 token 返回
     snapshotVersion（写路径后实测递增至 4）+ existingIds/version；不带 token→单体逐字节 401 错误体；
     经网关对字面路径 404。**check 勘误**：Phase 1 冒烟前缀路由 `/master-data/**`（StripPrefix=1）仍可
     使 `/master-data/internal/master-data/**` 携带合法 JWT 穿透网关（只读、仍需登录，非鉴权绕过，
     但违背"内部端点不外暴"意图；planning/execution 冒烟路由同款隐患）→ Phase 4 必修：网关增加
     `/internal/**` 显式拒绝（404 体），并复核冒烟路由边界。
   - 权限：无 token 经网关→逐字节 401；ptester（common 角色）业务端点（/demand/customer、/demand/product、
     /pps/product-route）200（业务域仅要求登录——quality spec 冻结现状），/system/menu/list→403
     `没有权限，请联系管理员授权`。
5. 契约 diff（临时单体 8082 自当前源码 spring-boot:run 拉起；8081 用户常驻实例未动）：
   脚本 `scratch/phase3/contract_diff.py`（双端各自建数、归一化雪花 id/时间戳、键集+归一值双重比对）
   **12/12 PASS**（customer list/{id}、product list、order list(本运行行)/{id}（check 后再验）、
   orderLine list/{id}、release/cancelRelease、缺失资源错误体、缺 orderId 错误体）+ 补充 ASCII 同名
   product/list 逐字段 PASS。日志 `scratch/phase3/diff_final.log`。
   **显式差异（3 类，均已记录）**：
   a. **单体冻结缺陷（非本次引入）**：单体 `/demand/product` 创建+详情 与 `/pps/product-route` 全部端点
      运行时 500 `数据库操作异常：...Not Found TableInfoCache.`——ProductMoldParam/ProductRoute 在单体无
      Mapper 注册（Db 工具链必需 TableInfo）。服务侧因完整 Mapper 集而可用：属"修复"，已按差异记录；
      统一切换评审时按"冻结缺陷修复"归类，不属行为回归。
   b. **刻意新增**：orderLine 引用不存在产品→单体接受、服务拒绝（Phase 3 验收项"跨域引用校验"）。
   c. **删除级联重排**：服务删订单行不触碰 production_batch（planning 权属），Phase 4 接线。
6. 清理：本次启动的 6 个 JVM（4 服务+临时单体及其 mvn 父进程）全部终止，8080/8082/8101-8103 端口释放；
   nacos/rabbitmq 容器停止；既有 mysql/redis/ELK 容器与 8081 用户单体未受影响（8081 实测 200）；
   master_data_db/demand_db 已重置为种子空库；diff 测试行已双向清理。

**遗留/移交 Phase 4**：
- **必修：网关 `/internal/**` 显式拒绝**（check 发现 Phase 1 冒烟前缀路由可穿透到内部契约端点，见上）。
- master-data `hasBlockingTasksForProduct` 当前恒 false（启用路线删除保护缺失）→ 需 demand 契约
  listOrderLineIdsByProduct + planning 契约 hasBlockingTasks。
- demand 删行不再级联删批次 + `ProductionBatchView` 恒空 → planning 契约/事件填充与补偿。
- Feign 服务身份令牌（无请求上下文的异步调用，如排程任务）随 Phase 4 补充；当前透传用户 JWT。
- Sentinel 规则 Nacos 持久化、OpenAPI 网关聚合正式路由仍挂起（沿 Phase 2 遗留；springdoc 各服务已启用，
  网关 swagger urls 仍指 Phase 1 前缀路由，统一切换前收敛）。

## Phase 4: Planning

- [x] 迁移批次、工序任务、资源需求、派工、异步排程任务与算法。
- [x] 使用批量契约和版本化输入快照加载 Demand/Master Data，避免 N+1 RPC。
- [x] 建立排程幂等、超时、并发互斥和结果一致性规则。
- Validation: 现有排程单测全部迁移；补充服务集成、跨班次、并发、超时、远程依赖失败和基准性能测试。
- Rollback point: 对同一固定数据集比较单体与微服务排程结果；关键差异未解释前不得进入下一阶段。

### Phase 4 执行记录（2026-09-15，含中断续作审计）

**续作说明**：前次中断遗留的部分工作（product-demand-api / product-planning-api 契约模块、demand
内部契约端点与 PlanningBatchClient、master-data RouteDeleteGuard 与契约扩展）经审计后补齐接线，
其余（product-planning 服务本体）为本次实现。

**续作审计发现并修复**：① `MasterDataApplication` 缺 `@EnableFeignClients`（RouteDeleteGuard 注入
demand/planning 两个 Feign 客户端，启动即失败）；② `DemandApplication` Feign 扫描范围未含
`com.product.planning.api`（PlanningBatchClient 注入失败）；③ `InternalMasterDataController` 缺
`Calendar` 实体 import（编译失败）；④ demand `OrderLineMapper` 缺 `@Param` import。

**产出**：
- `product-services/product-planning`（8104，planning_db + planning_svc）：批次/工序任务/派工/
  排程任务/算法全量移植 —— 实体 7、Mapper 7(+XML 4，新增 TaskResourceRequirementMapper，见下)、
  路由规则与标准工时模型（RouteRuleRegistry/Setup/Inject/Post + TM_*）、TaskSchedulingCalculator
  （算法冻结，与单体仅包名/DI 差异）、TaskSchedulingQueryService（本地表查询保留，跨域改契约）、
  SchedulingSnapshotLoader（新，契约快照）、TaskSchedulingCoordinator（新增加载基线+落库前漂移复核）、
  TaskAssignmentPersistenceService、ScheduleJobServiceImpl/ScheduleJobTimeoutService、
  ProductionBatch/OperationTask/TaskAssignment 三控制器（/pps/batch、/pps/task、/pps/assignment 路径冻结）、
  InternalPlanningController（batches/by-order-lines、by-order-lines/delete、has-blocking-tasks）。
  排程并发互斥 PlanningRedisLock（SET NX PX + 持有者令牌 + Lua 释放 + lock() 看门狗续期；Redisson
  不在任一 BOM 内故按同语义自实现；key 前缀 `planning:`，三把锁与单体一一对应）；线程池参数与单体
  ThreadPoolConfig 一致（core50/max200/queue1000/CallerRuns）。
- **版本化输入快照（显式规则）**：demand/master-data 批量响应信封携带各自 `snapshotVersion`
  （demand_data_version / master_data_data_version 单调计数）；排程启动 `captureVersions()` 采基线，
  计算完成后、事务落库前 `verifyUnchanged()` 重读两域计数，任一漂移即显式失败（任务保持 READY，
  重新发起即重试），绝不部分落库。每次排程远程调用有界（≤10 次，与任务数无关），无跨库访问。
- **服务身份令牌（ADR-0003 最小实现）**：identity 新增 `POST /internal/identity/service-token`
  （免验证码服务凭据登录，同一 AuthenticationManager/BCrypt/RS256 签发链；identity_schema.sql
  新增种子账号 planning_svc，非基线种子）+ SysLoginService.loginWithoutCaptcha；planning 侧
  ServiceIdentityTokenProvider（缓存至过期前 60s）+ PlanningFeignAuthInterceptor（请求线程透传
  用户 JWT，无上下文的异步线程改携服务身份 token —— 与 demand/master-data 的 FeignForwardAuthConfig
  共存）。
- **网关 /internal/** 显式拒绝（Phase 3 check 必修项）**：InternalPathDenyFilter（GlobalFilter，
  HIGHEST_PRECEDENCE）—— 请求路径任一段等于 `internal` 即 404 统一错误体 + X-Trace-Id，封堵
  冒烟前缀路由（/master-data/**、/demand-svc/**、/planning/**、/execution/**）对服务内部契约端点的
  穿透；baselines §1.2 外部路径无 internal 段，外部语义不变；服务间 Feign 经 Nacos 直连不受影响。
  另新增 planning 正式路由（/pps/batch、/pps/task、/pps/assignment → lb://product-planning）。
- demand/master-data 接线（部分工作补齐后生效）：订单行详情批次视图经 planning 契约填充
  （planning 不可用降级空列表并告警）；删订单行先经 planning 契约级联删批次（fail-closed，与单体
  "先删批次后删行"次序一致）；拆批/改批数量预占用经 demand 契约（SQL 与单体
  OrderLineAllocationMapper 逐字一致，本地失败负 delta 补偿）；启用路线删除保护经
  demand+planning 两段契约（任一不可用 fail-closed 拒绝删除）；订单/订单行/排程相关写路径同事务
  bump demand_data_version。
- SQL：`planning_schema.sql`（排程 7 表逐字节自根 schema.sql + AUTO_INCREMENT=100 保留 +
  planning_svc 账号 DML-only）；.env.example 补 PLANNING_DB_*/PLANNING_SERVICE_IDENTITY_*。

**验证结果（本机实测，temurin 17.0.20+8 / Maven 3.9.16）**：
1. 根构建（回滚点）：`mvn -DskipTests compile` **27/27 模块** BUILD SUCCESS；git status 除
   product-services/**、scratch/**、.env.example、.trellis/** 外零改动（单体未动）。
2. 离线测试（复核修正后最终 clean run）：`product-services` 下 `mvn clean test` **12/12 模块
   BUILD SUCCESS，147 用例全绿**（初版 139；check 修正新增 gateway 3、identity 3、planning 2 条）
   （cloud-common 19、cloud-security 11、gateway 16、identity 28、master-data 6、demand 15、
   planning 48、execution 4；日志 scratch/phase4/phase4_clean_test_v2.log；初版 139 条记录见
   phase4_clean_test.log）。
   **单体排程单测迁移**：product-pps 现有 9 个测试类全部迁移 —— 8 个迁入 planning
   （RouteOperationValidatorTest 4、RouteRuleRegistryTest 5、ChangeoverCalculatorTest 3、
   InjectDurationCalculatorTest 2、OperationResourceRequirementBuilderTest 1、
   TaskAssignmentPersistenceServiceTest 2、TaskSchedulingCalculatorTest 14、
   TaskSchedulingQueryServiceTest 4 = 35）+ ProductRouteServiceValidationTest 2 迁入 master-data
   （路线写路径校验归 master-data）= **37 个单体测试全部迁移且全绿**；planning 另新增
   SchedulingSnapshotLoaderTest 4（AVAILABLE 过滤/产品映射含路线工序排序/漂移拒绝/版本一致）、
   PlanningRedisLockTest 3。
3. 实机（nacos v3.0.3 + rabbitmq 3.13 compose；既有 mysql 33066/redis 6380；identity 8101 /
   master-data 8102 / demand 8103 / planning 8104 / gateway 8080 全注册 PRODUCT_GROUP、健康 UP；
   临时单体 8082 自当前源码拉起）。
4. **对拍（回滚点核心）**：同一固定数据集（SQL 种子：日历/2 机台/2 模具/兼容矩阵/人员+能力/
   工位/换型规则/2 产品含模具参数与启用路线/2 订单 4 订单行；两轮排程 EARLIEST_START +
   DUE_DATE_PRIORITY，相同 assignmentStart=2026-09-15 09:00:00）。
   **单体侧 live 调度不可用（冻结缺陷，非本次引入）**：单体 ProductRoute/ProductMoldParam/
   MachineMoldCompatibility/ResourceCapability/ChangeoverRule 无注册 Mapper，Db 工具链抛
   `Not Found TableInfoCache`（8082 实测 /pps/product-route/list 500、scheduleAllAsync FAILED），
   与 Phase 3 已记录的 TableInfoCache 冻结缺陷同类。故对拍基线改由**单体现有源码构件的进程内
   真实算法 harness**（scratch/phase4/mono-harness，独立 POM 仅消费 ~/.m2 单体构件，零单体改动）
   计算，与微服务全栈实测（经网关、含异步/契约/漂移复核全链路）**逐字段比对：12 任务 ×
   [状态 SCHEDULED + 派工主行（机台/模具/人员/工位、计划起止到秒、资源序号）+ 派工资源明细]
   全部一致**（PARITY OK），覆盖跨班次顺延（1800min 任务整体顺延至下一班次）、级联选模
   （机台兼容模具最早可用）、人员能力匹配、换型时间（不同模具 40+换料 10=40~50min 顺延）、
   依赖链约束、DUE_DATE_PRIORITY 交期排序。
   **差异分类**：a) 冻结缺陷修复（服务可用/单体同路径不可用）：generateTask 路线加载、
   排程输入快照加载、数量预占用跨服务化；b) 刻意新增：输入快照漂移复核（单体同库读一致性的
   替代）、服务身份令牌；c) 无未解释差异。
   generateTask 语义对拍：服务侧实生成任务 std_duration_min=1500/8/3000（qty=25 产品PP，
   SETUP=60×25、INJECT=A2 公式、POST=120×25）与单体模型一致；资源需求（SETUP=人员+机台、
   INJECT=人员+机台+模具、POST=人员+工位）一致。
5. **排程规则实测**：① 并发互斥 —— 4 并发 scheduleAllAsync，1 受理 3 拒绝
   （"当前已有排程任务在执行，请稍后再试"，提交互斥 + DB 状态双闸）；② 超时兜底 —— 插入过期
   PENDING/RUNNING 假任务行后手动 sweep，2 条均标 FAILED（"排程任务创建后长时间未执行"/
   "排程任务执行超时"）；③ 远程依赖失败 —— demand 停机 + READY 任务存在时 scheduleAllAsync →
   job FAILED（"需求服务不可用，无法加载排程输入快照"），6 任务保持 READY、派工行 0（无部分
   落库），重发即重试；④ 快照漂移 —— 排程期间并发写主数据（版本 56→59）→ job FAILED
   （"排程输入数据已变化(主数据域版本漂移 56->59)，本次排程已中止"）；⑤ master-data 侧
   RouteDeleteGuard fail-closed —— demand 停机时删除启用路线被拒（"需求服务不可用，无法确认
   工艺路线删除保护"）。
6. **网关 /internal/** 拒绝实测**：/internal/**、/master-data/internal/**、/planning/internal/**、
   /demand-svc/internal/** 四种形态均 404 统一错误体 + X-Trace-Id；业务路径 /pps/batch/list 正常
   200。单测 InternalPathDenyFilterTest 4 条覆盖。
7. **服务身份令牌实测**：identity /internal/identity/service-token 以 planning_svc 凭据换取 RS256
   token → demand/master-data 内部契约 200；全部异步排程成功轮次即经此通道认证。
8. 清理：本次全部 JVM（5 服务 + 临时单体 + 重启迭代）与 nacos/rabbitmq 容器停止移除，8080-8105/
   8848 端口全部释放；既有 product-mysql/product-redis 未动；对拍/探针测试行已从 product 库与
   服务库双向清理（服务库重置为种子空库，product 库逐表删除本会话创建行并核实 0 残留）。

**过程中发现并修正**（本次实现引入、已修复）：
① 移植的三控制器 package 声明未重写（com.product.pps.controller）导致不被扫描——/pps/assignment
   404；修正后重建。② TaskResourceRequirement 无注册 Mapper 使排程装配抛 Not Found TableInfoCache
   （单体 Db 工具链脆弱模式的等价暴露）——新增服务自有 Mapper。③ PlanningFeignAuthInterceptor
   以 RequestTemplate.path() 前缀判定内部契约漏判（不含 @FeignClient(path) 前缀）——改为无条件
   服务身份兜底。④ identity 重启换钥后缓存 token 失效（401-in-200 信封）——loader 增加
   evict+重试一次。⑤ generateTask 移植自单体的 .select 漏查 order_line_id（单体冻结缺陷）导致
   订单行恒空——修复并注释。⑥ ServiceIdentityProperties 双重注册导致启动失败——改为 @Component。

**trellis-check 复核修正（2026-09-15，HIGH 缺陷 + 记账修正）**：
1. **HIGH /pps/batch/list 跨库 join 缺陷（已修复）**：移植的 ProductionBatchMapper.xml
   selectProductionBatchVO LEFT JOIN order_line/customer_order/product（planning_db 无这些表，
   planning_svc 仅 planning_db 授权 → 任意分页查询运行时 1146/1142）。此前记录"业务路径
   /pps/batch/list 正常 200"为**误记**（该路径当时未被实际请求验证，日志无此请求），已更正。
   修复：XML 改为本库分页查询（orderId 过滤经 demand 契约解析订单行 ID 后 IN 过滤），跨域聚合
   字段（orderId/dueDate/productName，与单体 join 字段逐字段一致）由 ProductionBatchServiceImpl
   经 demand/master-data 批量契约填充（每页 demand 2 次 + master-data 1 次，无 N+1；契约不可用
   时跨域字段置空并告警，本库分页主行恒可读）。单体 planned 区间过滤的 date_format 单参非法 SQL
   与 endPlannedEnd 误配 beginPlannedEnd 两处冻结缺陷按字面意图修正实现（差异已记录）。
2. **网关拒绝形态 live 留痕**：本次 live 运行将字面 + 四种冒烟前缀形态的探测输出存档至
   scratch/phase4/gateway_deny_transcript.txt（含 X-Trace-Id 与统一 404 体）。
3. **InternalPathDenyFilter 加固**：逐段先剥矩阵参数（internal;x → internal）再 URL 解码
   （%69nternal → internal），畸形转义保留原文继续匹配；新增单测 3 条（变形拒绝/畸形转义/原始
   未解码路径断言）。MockServerHttpRequest 字符串重载会把 '%' 再编码（%25），测试用 URI 重载。
4. **新增离线测试**：identity InternalServiceTokenControllerTest 3 条（错误凭据 → 与单体登录
   同源错误体 HTTP 200 + code 500 "用户不存在/密码错误"；种子 planning_svc 正确凭据 → RS256
   token、sub=planning_svc、permissions 空集）；planning SchedulingSnapshotLoaderTest 增 2 条
   （401 信封形态 → evict + 重试一次恢复；重试仍失败 → 单次重试后按响应异常处理）。
6. **/pps/batch/list live 复验（修复后，经网关 + 有效 token）**：无过滤 total=2 且
   orderId/dueDate（demand 契约）与 productName（master-data 契约）正确填充——字段集与单体
   ProductionBatchVO 一致（batchId/orderLineId/batchQty/status/plannedStart/plannedEnd/
   orderId/dueDate/productName）；orderId=9001 过滤仅返回该订单下批次（契约解析 IN 过滤）；
   orderId=9999 返回 total=0 空集；均为 HTTP 200 + TableDataInfo 信封。语义基线说明：单体
   /pps/batch/list live 链路受 TableInfoCache 冻结缺陷影响不可用，字段/语义比对以单体源码
   （join 字段集 + VO 定义）与本任务 in-process harness 为基线，如实记录。transcript 存档
   scratch/phase4/batch_list_transcript.txt 与 gateway_deny_transcript.txt（字面 + 4 前缀形态 +
   %69nternal / internal;x 变形 + 业务路径对照，含 X-Trace-Id）。对拍在最终 jar 上重跑确认
   PARITY OK（修复不触及调度路径）。
5. **记账修正**：单体测试类数为 9（8 迁 planning + 1 迁 master-data），非"10 中取 9"；
   identity_schema.sql planning_svc 注释改为精确表述（挂 common 角色，权限串解析为空集，非
   "无任何菜单"）。另注：服务端 ServiceException/RuntimeException → HTTP 200 + code 500 错误体
   为单体冻结的全局兜底契约（error-handling.md），各阶段沿用，非 Phase 4 回归。

**遗留问题（不阻塞 Phase 5）**：
- 单体 generateTask/调度链路在当前源码 live 不可用（TableInfoCache 冻结缺陷族），统一切换评审时
  按"冻结缺陷修复"归类，不属行为回归。
- planning 的 Excel 导入导出为控制器忠实移植，未做对拍（单体同路径受冻结缺陷影响）。
- ProductionBatchServiceImpl 拆批"先预占后落批"的补偿窗口（落批失败补偿释放失败时需人工对账）
  已记录告警日志；Phase 5 对账任务可收敛。
- identity_schema.sql 种子用户数 1→2（planning_svc，Phase 4 新增，非基线种子）。
- Sentinel 规则 Nacos 持久化、OpenAPI 网关聚合收敛、span 导出器仍沿 Phase 2/3 遗留清单。

## Phase 5: Execution And Event Consistency

- [x] 迁移任务/资源事件，建立 RabbitMQ exchange/queue/binding、版本化 envelope、publisher confirm、Outbox、幂等消费、重试和死信。
- [x] 将进程内状态刷新链改为 Planning/Demand 各自消费事件并更新本域状态。
- [x] 建立对账、补偿和人工重放工具及审计记录。
- Validation: 开始/暂停/恢复/完成/异常全链路测试；重复、乱序、延迟、MQ 暂停恢复、消费者失败和补偿测试通过。
- Rollback point: 可清理测试消息并从备份重建服务库；状态对账不为零不得统一切换。

### Phase 5 执行记录（2026-09-15，含中断续作审计）

**续作说明**：前次中断遗留的部分工作（product-cloud-messaging 基础设施、execution 服务本体、
planning/demand 事件消费与对账、ops 端点、schema 基础设施表、gateway 路由、全部单测）经审计后
基本完整，本次补齐缺陷并完成全部验证。**本次会话代码改动**：仅 3 处——①
`InternalExecutionController` 重复 import 清理；② **OutboxRelay 投递缺陷修复（live 实测发现，
HIGH）**：原实现经 RabbitTemplate 的 Jackson2JsonMessageConverter 发 `byte[]`，被序列化成
Base64 字符串，消费端 `EventEnvelope` 反序列化必败（首个 task 事件即重试耗尽进 DLX）——改为
直接构造 AMQP Message（原始 JSON 字节 + application/json + eventId/eventType header +
CorrelationData），`EventReplayService.replayDeadLetter` 同款修复；③ `product-services/README.md`
补事件基线文档。审计确认无缺件：envelope v1（eventId/eventType/version/occurredAt/producer/
aggregateId/correlationId/payload，只加不改演进策略）、事务内 Outbox（业务行+outbox 行同一
本地事务，JdbcTemplate 与 MyBatis 共享 DataSourceTransactionManager）、OutboxRelay 轮询 +
publisher confirm（成功才 PUBLISHED，失败退避 1s×2^n 封顶 5min 共 8 次后 HALTED）、eventId
消费去重（consumed_event，UNIQUE(consumer_group,event_id)）+ 同聚合 occurredAt 单调性守卫
（过期事件记 STALE 丢弃）、有界消费重试（3 次）→ DLX → 死信审计落库（审计未落库不 ack）、
outbox/DLQ 双路人工重放（新 eventId + 原 correlationId/payload）、ops_audit 全动作留痕。

**产出（Phase 5 全量）**：
- `product-services/product-cloud-messaging`（新库模块，ADR-0004）：envelope 编解码（私有
  ObjectMapper）、Outbox（Dao/Publisher/Relay）、ConsumedEventRecorder、MessagingTopology
  （exchange/queue/binding/DLX 声明，消费方配置驱动、双侧幂等）、DeadLetterAuditor、
  EventReplayService、OpsAuditService、MessagingAutoConfiguration（`product.messaging.enabled=true`
  才装配；事件容器工厂逐条成功后确认 + 重试拦截器；DLQ 容器失败重回队列）。
- `product-execution`（8105，execution_db + execution_svc）：task_event/resource_status_event
  2 表移植（`/execute/event` 路径冻结，CRUD/导出导入/start/pause/resume/complete 与单体
  逐字段同形）；命令改造——任务存在性/派工机台经 planning 只读契约
  `PlanningTaskApi.taskRuntimes`（fail-closed，任务不存在/契约不可达 → 命令 false 不留事件，
  与单体 update 0 行同语义）；命令内写 task_event + outbox（同事务），resource_status_event
  登记端点 + resource.status.changed 出站（仅记录/告警用途）。
- 状态刷新链事件化（无跨域同步调用）：execution → `task.status.changed` → planning 消费
  （任务状态无条件映射 + BatchStatusResolver 批次聚合【单体 ProductionBatchStatusRefresher
  逐字移植】+ 变更才发布）→ `batch.progress.changed` → demand 消费（planning_batch_state
  投影 upsert + DemandStatusResolvers 订单行/订单聚合【单体两 Refresher 逐字移植】+
  order_line.progress.changed 出站预留）。demand 删订单行/订单随行清理投影。
- 对账/补偿/重放：planning/demand 各自本域对账定时任务（漂移 → ops_audit RECON_DRIFT →
  自动修复 RECON_HEAL）；ops 端点（planning/demand：replay-outbox、replay-dlq、
  recompute-batch/line/order-status、adjust-allocation【Phase 4 遗留补偿窗口的人工收敛通道】、
  health 巡检；execution：outbox 巡检、replay-outbox、资源事件登记/追溯）；运维对账脚本
  `scratch/phase5/recon.py`（跨域 X1 批次 vs 投影、X2 任务事件流水 vs planning 终态、
  X3 allocated_qty vs SUM(batch_qty)、P1/D1/D2 本域不变式复核 + heal/replay 命令）。
- SQL：`execution_schema.sql`（执行 2 表逐字节 + 4 张基础设施表）；planning/demand schema
  追加同名基础设施表（服务权属，根 schema.sql 零改动）。
- gateway：/execute/event 正式路由 + swagger 聚合项；.env.example 补 EXECUTION_DB_*。
- 单测新增 34 条：cloud-messaging 6（envelope 编解码/演进、outbox 退避语义）、planning 10
  （消费编排幂等/STALE/非法 payload 6 + 批次聚合 4）、demand 11（消费编排 5 + 行/单聚合 6）、
  execution 7（单体 5 条语义移植 + 事件 payload/fail-closed 断言）。

**事件拓扑（冻结，ADR-0004 §5，README.md 有全表）**：exchange `execution.events`/
`planning.events`/`demand.events`（direct/durable），routing key = eventType；queue
`product-planning.execution`（task.status.changed + resource.status.changed）、
`product-demand.planning`（batch.progress.changed）；每 queue 配 `{queue}.dlx`/`{queue}.dlq`，
死信审计监听器落库后 ack。envelope v1 演进策略：只加不改（新增可选字段不升版本；语义/类型
变更必须升 version 并新增 eventType）。

**验证结果（本机实测，temurin 17.0.20+8 / Maven 3.9.16）**：
1. 根构建（回滚点，relay 修复后最终 clean run）：`mvn -DskipTests clean compile`
   **28/28 模块** BUILD SUCCESS（27+cloud-messaging）；单体/根 pom/root schema.sql/
   compose.dev.yml 零改动（git status 仅 product-services/**、.env.example、README、
   .trellis/task 记录）。
2. 离线测试（最终 clean run）：`product-services` 下 `mvn clean test` **13/13 模块
   BUILD SUCCESS，181 用例全绿**（cloud-common 19、cloud-security 11、**cloud-messaging 6**、
   gateway 16、identity 28、master-data 6、demand-service 26、planning 58、execution 11；
   Phase 4 为 147 条 → +34；日志 scratch/phase5/phase5_clean_test_v2.log）。
3. **状态对拍（回滚点核心，scratch/phase5/parity_*_transcript.txt + parity_compare_transcript.txt）**：
   同一数据集（order 9701/line 9711/batch 9721/tasks 9731+9732，SCHEDULED/RELEASED/CONFIRMED
   起点）+ 同一事件序列 10 步（start→pause→resume→start→complete→complete→幽灵任务→对 DONE
   任务 pause→resume→complete），单体侧为**当前源码 clean-room 临时实例**（8082，独立库
   product_phase5_mono = 根 schema.sql 原样初始化，SPRING_DATASOURCE_URL 环境覆盖，不改任何
   配置文件），微服务侧为全栈事件链（execution 8105 命令 → RabbitMQ → planning/demand 消费，
   每步等 outbox PENDING=0 且状态稳定后快照）。**10/10 步逐字段一致（PARITY OK）**：
   task1/task2/batch/line/order 五状态 + task_event 事件序列（event_type + resource_id，
   含派工机台 9741 回填）+ 命令失败语义（幽灵任务双侧同为 HTTP 200 + `{"msg":"操作失败",
   "code":500}` 且不留事件行）。异常路径语义对齐：DONE→PAUSE 无条件映射引发批次/订单行/订单
   级联回退（DONE→IN_PROCESS→IN_PRODUCTION→IN_PRODUCTION）两侧一致；完整级联
   （start→全链 IN_PROCESS/IN_PRODUCTION、双双 complete→全链 DONE）两侧一致。
   **差异分类**：a) 冻结缺陷修复（非本次引入）：见第 4 条单体 live 不可用记录；
   b) 刻意新增/架构性：命令成功时点任务状态尚为异步推进（单体同事务同步）、demand 以
   planning_batch_state 事件溯源投影替代跨库读批次表、跨域引用校验/投影清理为新增防御；
   c) 无未解释差异。
4. **单体 live 链数据依赖缺陷（冻结缺陷族新增实锤，非本次引入）**：用户 live 库 product 的
   ID 列为 VARCHAR(64) 且存量含非数字 ID（customer_order.order_id='O1'/'O2'），单体刷新链
   `Db...in(ProductionBatch::getBatchId, <Long>)` 数字比较触发全列隐式 cast →
   `Data truncation: Truncated incorrect DOUBLE value: 'O1'` → /execute/event/start|pause|
   resume|complete 全部 500 回滚（8082+live 库实测，transcript parity_mono_transcript.txt
   初版）。属单体存量数据形态缺陷，微服务侧服务库 BIGINT 无此问题；统一切换评审按"冻结缺陷
   （live 数据触发）"归类，对拍基线改用 clean-room 库（第 3 条）。
5. **异常语义实测证据清单（全部 live，transcripts 在 scratch/phase5/）**：
   - 场景A outbox 有界重试→HALTED→人工重放（scenarioA_outbox_halted.txt）：停 RabbitMQ 后
     登记资源事件（命令 200，本地事务已提交）→ 退避重试 8 次（1+2+4+8+16+32+64s）→ HALTED；
     Rabbit 恢复后 HALTED 行不被自动重投（selectPending 仅 PENDING）；ops replay-outbox →
     新 eventId PUBLISHED + planning 消费 APPLIED + ops_audit REPLAY_OUTBOX(admin)。
   - 场景B 重复/过期/乱序（scenarioB_idem_order.txt）：同 eventId 重投 → 幂等跳过
     （consumed_event 不增、日志"重复事件跳过(幂等)"、状态不变）；occurredAt 早于最后应用
     4.5h 的延迟事件 → outcome=STALE 记录丢弃、状态不回退；全新聚合 9733 先 FINISH(10:00)
     后迟到 START(09:00) → FINISH APPLIED、迟到 START STALE 丢弃、终态 DONE 不变。
   - 场景C 消费者失败→DLX→审计→重放（scenarioC_dlx.txt）：毒消息（payload 缺 targetStatus）
     → 有界重试 3 次耗尽 → DLX → dead_letter_audit 落库（x-death reason=rejected）→ DLQ
     排空（审计后 messages=0）；replay-dlq → 原行标 replayed=1 + replay_event_id，毒消息按
     设计再次死信形成新审计行（不丢不吞）+ ops_audit REPLAY_DLQ。
   - 场景D MQ 暂停-恢复（scenarioD_pause_resume.txt）：停 demand → execution 命令成功、
     planning 第一跳正常推进、batch 事件持久化积压（messages=1, consumers=0，零丢失）→
     重启 demand → 消费者重连排空（messages=0, consumers=1）→ line/order 状态收敛 APPLIED。
   - 场景E 对账/补偿/分配窗口（scenarioE_recon_heal.txt）：注入 line 状态漂移 → 需求域对账
     检出（RECON_DRIFT lines=[9711,9712]）→ 自动修复（RECON_HEAL）回 DONE；注入投影漂移 →
     本域对账按冻结规则修复行状态、跨域 X1 由 recon.py 报告 → planning 侧 replay-outbox
     重放批次事件修复投影并级联重算；allocated_qty 漂移（X3 检出 30 vs 20）→
     adjust-allocation 幂等设值 → X3 清零 + ADJUST_ALLOCATION 审计（场景当时报告里的
     "3 vs 2" 为 recon.py 取值串下标缺陷的截断显示，漂移判定本身有效；该缺陷已修复并
     定向回归：预期 planned=30 vs alloc=20 如实上报，探针行已清理）。
     最终 recon.py 13 项检查 drifts=[]（recon_final_report.json；保留证据性遗留：execution
     HALTED 1 行、planning 未重放死信 1 行，均为场景 A/C 实测产物）。
   - 网关契约（gateway_transcript.txt）：/execute/event/list 经 8080 返回 TableDataInfo
     （Long→String 契约）；无 token 直连 8105 → HTTP 200 + 单体逐字节 401 体；
     /internal/** 经网关字面与前缀形态均 404。
6. 进程安全：全部本次启动 JVM（5 服务 + 迭代重启 + clean-room 单体及其 mvn 父进程）终止，
   8080/8082/8101/8103/8104/8105 释放；product-nacos/product-rabbitmq 容器停止（前次中断
   运行遗留的 6 个 JVM 先行清点终止，pids_stale_leftovers.txt）；用户 product-mysql/
   product-redis 容器与 8081 端口未触碰；product 库本会话测试行逐表删除核实 0 残留、
   clean-room 库已 DROP、三个服务库重置为种子空库（业务表 0 行 + 基础设施表清空）。

**遗留问题（不阻塞 Phase 6）**：
- Sentinel 规则 Nacos 持久化、OpenAPI 网关聚合收敛、span 导出器（OTLP）仍沿 Phase 2/3/4
  遗留清单（Phase 6 统一切换前完成）。
- order_line.progress.changed 为预留出站（无消费方，单体现状订单行状态仅由批次聚合驱动，
  事件仅作审计/未来扩展）——如有跨域消费需求需先评审契约。
- planning_batch_state 投影为服务权属派生表（可由事件重放/对账重建），Phase 6 数据校验
  脚本需包含投影与 planning 批次事实的一致性核对（recon.py X1 已实现）。
- 单体 live 库 VARCHAR ID + 非数字存量的数据依赖缺陷（第 4 条）如需在单体内修复属新功能，
  需单独评审；统一切换以服务侧行为为准。
- **check 交接 Phase 6**：① OutboxRelay 裸 AMQP 线格式（content-type/头/无 Base64）目前仅
  live transcript 证明，需补离线回归测试把该 live 发现的缺陷固化为测试；② ops/重放/对账端点
  现为"JWT + 网关拒绝 + 审计"但无 admin 角色门禁（与冻结基线一致），统一切换评审时需显式决策。

## Phase 6: Unified Cutover

- [x] 从 Gateway 入口执行完整“登录 -> 订单 -> 批次 -> 排程 -> 执行 -> 完工”E2E。
- [x] 执行安全、故障注入、容量、日志指标追踪和告警验收。
- [x] 演练离线备份、schema 初始化/迁移、数据校验、启动顺序和整套回滚。
- [x] 更新开发运行、部署、故障处理和数据补偿文档。
- Validation: `mvn verify`、服务集成与契约测试、E2E、Compose clean-room 启动、迁移校验和回滚演练全部通过。
- Rollback point: 关闭微服务入口，恢复旧库备份和原单体构建；问题修复后重新进行完整统一切换演练。

### Phase 6 执行记录（2026-09-15，含中断续作与 live 调试）

**范围**：统一收口 Phase 0–5 全部遗留清单 + 全链 E2E/验收/演练/文档；证据全部存
`scratch/phase6/`（transcript 与脚本），数字均来自 clean run 与实测 transcript。
**硬约束复核**：git status 显示单体/根 pom/根 schema.sql 零改动；compose.dev.yml 唯一变更
为新增 jaeger 服务（OTLP 导出后端，13 行，记录于本节）；单体可构建（回滚演练中
`mvn -pl product-server -am package` 通过并以 spring-boot:run 实机冒烟）。

**遗留收口逐项（Phase 2/3/4/5 清单）**：
1. **Sentinel 规则 Nacos 持久化**：网关新增 `sentinel-datasource-nacos`（SCA BOM 1.8.9），
   dataId=`product-gateway-flow-rules.json`/group=PRODUCT_GATEWAY/rule-type=`gw-flow`；
   Nacos 已发布规则热加载覆盖内存规则，未发布时 `product.gateway.sentinel.routes` 属性兜底。
   live 踩坑两枚：`rule-type` 必须为 `gw-flow`（SCA RuleType 枚举无 gateway-flow）；
   属性名是 **`group-id`** 而非 `group`（写错静默回退 DEFAULT_GROUP——首启日志
   `[subscribe] ...+DEFAULT_GROUP+dev` 暴露后修复）。实测：发布 QPS=2 规则 → 网关热加载 →
   连发 8 次前 2 次 200、其余 429 统一错误体；恢复 QPS=200 后解除（security_probes_transcript.txt）。
2. **OpenAPI 网关聚合 + 路由收敛**：移除 Phase 1 全部 StripPrefix 冒烟前缀路由（/identity/** 等，
   该移除同时消灭 Phase 3 发现的前缀路由穿透面）；新增 /master/calendar、/master/resource/machine
   正式路由（baselines §1.2 原路径，此前漏配）；5 条文档路由 `/{service-prefix}/v3/api-docs**`
   （StripPrefix=1）+ 网关 permitPaths 放行单段前缀形态，swagger-ui.urls 聚合可达（匿名）。
   单测新增 removedPhase1PrefixRoutesShouldNoLongerRoute（业务前缀落统一 404 体）。
3. **OTLP span 导出器**：6 个可部署模块新增 `opentelemetry-exporter-otlp`（Boot BOM 管理）+
   `management.otlp.tracing` 配置（`OTLP_TRACES_ENABLED` 默认 false、`OTLP_TRACES_ENDPOINT`）；
   compose.dev.yml 新增 jaeger all-in-one（:4318/:16686）。实测：一次 Gateway 入口请求的
   traceId 同时出现在目标服务 JSON 日志与 Jaeger（trace 内同时含 product-gateway 与目标服务
   span，5/5 服务）；Jaeger 服务清单 6/6（observability_transcript.txt）。
4. **ops 端点 admin 门禁（决策 + 实现）**：决策为 ops 运维端点（可重放事件/人工改写状态）追加
   管理员门禁 `@PreAuthorize("@ss.hasPermi('*:*:*')")`——planning/demand OpsController 类级 +
   execution 两个 `/ops/*` 方法级（execution 资源事件契约端点保持 token 语义不变）。
   理由：网关 /internal/** 拒绝 + 管理员门禁 + ops_audit 留痕三层约束；服务身份令牌
   permissions 为空集不可调用；失败语义走单体权限契约（200 + code 403）。离线契约测试 4 条
   （反射断言注解存在性/位置）；live 实证：admin 200、planning_svc 与服务身份令牌均 403。
5. **OutboxRelay 裸 AMQP 线格式离线回归**（Phase 5 check 交接项）：新增
   OutboxRelayWireFormatTest——Mockito 捕获 `rabbitTemplate.send(...)` 的 Message，断言
   content-type=application/json、contentEncoding=UTF-8、PERSISTENT、eventId/eventType 头、
   body 为裸 JSON 对象字节（'{' 开头、可解析、字段与信封一致、无 Base64 形态），2 条测试
   固化 Phase 5 live 发现的 Base64 缺陷。
6. **identity 生产 JWT 密钥注入 + 轮换演练**：openssl 生成 PKCS#8 密钥对，经
   `IDENTITY_JWT_PRIVATE_KEY`/`IDENTITY_JWT_PREVIOUS_PUBLIC_KEY` 注入，四步轮换全部实测通过
   （KEY1 签发 → KEY2+KEY1 公钥窗口（/jwks 双 kid，旧 token 200、新登录 kid2）→ 窗口关闭
   （旧 token body code=401）→ 恢复开发默认）。注意点已文档化：认证失败为 HTTP 200 + body
   code 401（探测须看 body code）；网关/服务 JWKS 按 kid 缓存，撤销已缓存 kid 需刷新缓存
   （演练中重启网关）。transcript：scratch/phase6/jwt_rotation_transcript.txt。
7. **单体 live 库 VARCHAR-ID 缺陷处置结论**：写入 `docs/微服务运维手册.md` §6.2——属单体存量
   数据形态缺陷（VARCHAR ID + 非数字存量触发隐式 cast 500），微服务侧 BIGINT 无此问题，
   **统一切换以服务侧行为为准**，不在单体内修复（如修复属新功能需单独评审）。

**验证结果（全部实测）**：
1. **构建/测试（最终代码态 clean run）**：根 `mvn clean verify` **28/28 模块 BUILD SUCCESS**，
   用例 **243 全绿**（单体 55 + product-services 188；product-services 较 Phase 5 +7：线格式 2、
   ops 门禁 4、网关路由收敛 1）；日志 phase6_root_verify_final.log（另有
   phase6_services_verify.log 单独 13/13 模块 188 条）。
2. **全链 E2E（全程 Gateway 8080）**：`scripts/e2e_full_chain.py`，**42 项断言全 PASS**
   （transcript e2e_full_chain_transcript.txt）：验证码登录（读服务端真实验证码值）→ 建客户/
   产品（模具参数+3 工序启用路线）/日历/机台 → 第二启用路线被单活跃约束拦截 → 建订单/
   订单行（不存在产品跨域校验拒绝）→ 确认/释放 → 拆批（数量预占）→ 批次释放 → generateTask
   → 异步排程轮询 SUCCESS → 批次/任务/派工查询（跨域字段填充）→ start→pause→resume→complete
   事件链（三域状态逐级收敛断言）→ 幽灵任务失败语义（操作失败 + 不留事件行）→ 完工闭环
   （批次/行/订单 DONE）→ **快照漂移守卫**（排程期间并发 bump 主数据版本 → job FAILED
   「版本漂移」，120 探针任务全部保持 READY 无部分落库）→ **recon report 50 项检查 drifts=[]**
   （recon_final_transcript.txt）。探针数据（漂移批次/派工）已清理。
3. **安全验收**：`scripts/security_probes.py` **23 项全 PASS**（security_probes_transcript.txt）：
   无 token/伪造 token/篡改 payload（RS256 拒绝）/伪造内部头（X-User-*）均 401 单体逐字节体；
   /internal/** 经网关字面+变形（%69nternal、前缀、跨服务路径）6 形态全 404；
   非 admin（planning_svc）业务端点 200（冻结现状）、system 端点 403、**ops 端点 403**；
   admin ops 200；Sentinel Nacos 规则热加载 429（见遗留 1）；swagger 文档聚合匿名可达。
4. **故障注入**：`scripts/fault_injection.py` **30 项检查全 PASS**
   （fault_injection_transcript.txt）——demand 停机：503 统一体 + 排程 job FAILED（需求服务
   不可用）任务保持 READY，恢复后路由/排程收敛；master-data 停机：订单行 fail-closed
   （主数据服务不可用），恢复后创建/删除正常；planning 停机：网关 503（无实例）或 500
   （LB 缓存期连接拒绝，两种统一错误体形态均记录），execution 命令 fail-closed 不留事件，
   恢复后同一命令成功且事件链推进；RabbitMQ 停机：命令成功（本地事务）outbox 积压、planning
   不推进，恢复后 relay 排空 + 消费收敛；9 个探针任务全部完工后清理三库探针行，
   终态 **recon drifts=[] 且 outbox 全收敛**。
5. **可观测性**：`scripts/observability_check.py` 全 PASS（observability_transcript.txt）——
   同步链 5 服务各一条网关入口请求：X-Trace-Id = 服务 JSON 日志 traceId = Jaeger trace
   （gateway+服务双 span）；异步链：同一命令 traceId 作为 correlationId 贯穿
   execution→planning→demand 三域 outbox；/actuator/prometheus 6 端点输出
   http_server_requests + JVM 指标；Jaeger 服务清单 6/6。告警基线规则
   `deploy/alerts/product-microservices-alerts.yml`（引用指标族逐一实测存在，
   含抓取配置示例与 outbox/对账类告警的巡检通道说明）。
6. **容量基准（轻量，不设门槛）**：`scripts/capacity_bench.py`
   （capacity_bench_transcript.txt）——200 产品（路线+模具参数+6000 能力行）/50 订单/100 行/
   100 批次 → generateTask 经网关 1 次调用 2.1s 生成 300 任务 → scheduleAllAsync 提交至
   SUCCESS **总耗时 1.0s**（服务端 totalCostMs=426：计算 84ms + 落库 124ms，EARLIEST_START，
   批大小 200），派工 300 行落库；planning 进程 RSS 779MB→797MB（**+18MB**）。
7. **演练**：
   - **Clean-room 启动**：`scripts/clean_room_up.sh`（compose 起 nacos/rabbitmq/jaeger →
     v3 admin API 建 dev namespace → 发布 Sentinel 规则 → 5 个 schema 脚本重置 → 起 6 服务
     （OTLP 打开）→ 冒烟断言），6/6 注册、健康全 UP；执行两轮验证可重复性。
   - **备份/恢复**：`scripts/cutover_drills.sh backup|restore`——6 库 mysqldump
     （--single-transaction）→ drop/create/import → 表清单比对 77 张一致 + 关键表精确计数
     （backup_transcript.txt）。
   - **数据校验**：`scripts/validate_data.py`（validate_data_transcript.txt）——三域行数快照、
     X1 投影一致性/无孤儿投影、X2 事件流水 vs 终态、X3 预占数量、outbox/死信健康、
     recon.py 终局 drifts=[]，全 PASS。
   - **整套回滚**：`scripts/cutover_drills.sh rollback`（rollback_transcript.txt）——R1 关闭
     微服务入口（6 JVM 停、端口释放）→ R2 恢复 6 库备份（表清单一致 + 精确计数核对）→
     R3 单体可用冒烟（构建 + spring-boot:run，/captchaImage 200，登录接口可达）→
     R4 停止单体。**ROLLBACK DRILL PASS**。
8. **进程安全**：全部演练 JVM 经 pids.txt 记录、只清理自有进程；演练结束执行 stop_all.sh
   停服务与 compose 演练容器；既有 product-mysql/product-redis 容器与用户数据未受影响
   （product 库 dump→restore 为同内容往返，恢复后 sys_user=1/32 表核对一致）。

**文档产出**：`docs/微服务运维手册.md`（拓扑/启动顺序/备份恢复回滚/故障处理表/密钥管理/
已知单体缺陷处置/告警基线/部署注意）、`docs/数据补偿手册.md`（一致性模型/巡检/补偿动作/
人工重放/处置决策树）、`product-services/README.md` 更新（收敛后路由表、Sentinel Nacos 持久化、
OTLP、ops 门禁、轮换演练指针）、`.env.example` 补 OTLP 变量、`deploy/alerts/` 告警规则。
**新增 live 踩坑记录**（写入 README/手册）：Sentinel datasource `group-id` 属性名、
`rule-type=gw-flow`、单体普通 jar 不能 java -jar（回滚用 spring-boot:run）。

**任务完成度评估**：Phase 0–6 全部验证标准满足——ADR/基线评审、版本兼容最小验证（P0）、
骨架/认证/主数据需求/排程/执行事件各阶段验证（P1–P5，见各阶段记录）、统一切换验收
（P6：构建全绿 + E2E + 安全/故障/容量/可观测性/告警 + clean-room/备份恢复/数据校验/回滚
演练）全部通过。非阻塞遗留（均已记录）：order_line.progress.changed 为预留出站事件（无
消费方，需跨域消费先评审）；execution HALTED 1 行 / planning 死信 1 行为 Phase 5 场景
证据性保留；CI 尚未在远端 GitHub 实际触发验证（本地无法验证 runner 行为）。

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
