# ADR-0003: 认证（JWT/JWK）与内部调用身份传递

- 状态: Accepted
- 关联: design.md §4/§6、PRD R7、implement.md Phase 2

## 背景（现状证据）

- 签发与解析：`product-core/.../JwtUtils.java`（jjwt 0.9.1，`SignatureAlgorithm.HS512`，对称密钥 `token.secret`，来自环境变量，默认 300 分钟有效期）。
- Token 为自包含 JWT：claims 含 `userId`、`userName`、`permissions`（完整权限串列表）、`loginTime`、`expireTime`、设备信息；header `Authorization`，前缀 Bearer。
- 过滤链：`TraceMdcFilter → CorsFilter → JwtAuthenticationTokenFilter`（product-auth `SecurityConfig`）；匿名端点 `/login`、`/register`、`/captchaImage` + `@Anonymous` 收集；方法级鉴权用 `@PreAuthorize("@ss.hasPermi(...)")`。
- 依赖 jjwt 0.9.1 需 jaxb-api 2.3.1 兜底才能在 Java 17 运行，库本身必须升级。

微服务化问题：HS512 共享对称密钥意味着任何能"本地验签"的服务都必须持有签名私钥；任一服务泄露即全局伪造。

## 决策

1. **外部登录语义保持兼容**：`POST /login`（验证码、返回 AjaxResult + token）、`GET /getInfo`、`GET /getRouters`、token header/前缀、300 分钟 TTL、20 分钟静默刷新语义均不变（PRD R7）。
2. **改为非对称签名 RS256**：只有 Identity 持有私钥（环境 Secret 注入，不入库不入仓库）；Gateway 与各服务只持有公钥。
3. **公钥以 JWKS 发布**：Identity 暴露 `GET /jwks`（含 `kid`）；Gateway 和服务按 `kid` 匹配并缓存 JWKS，支持轮换（新旧 key 并存一个轮换窗口）。也可选将 JWKS 副本发布到 Nacos 配置以减少启动期依赖，主路径仍为 JWKS 端点。
4. **本地验签，两层校验**：
   - Gateway 前置校验签名 + `exp` + 基本声明，校验失败返回统一 401 错误体；
   - 服务端仍用 `JwtAuthenticationTokenFilter` 等价物做本地验签并重建 SecurityContext，`@PreAuthorize("@ss.hasPermi(...)")` 与权限串格式 `{module}:{resource}:{action}` 不变；权限事实源仍为 Identity（服务信任 token 内嵌 permissions，新鲜度由 TTL 约束，与现状一致）。
5. **claims 兼容迁移**：保留现有自定义 claims（`userId`、`permissions`、`loginTime`、`expireTime` 等），补充标准 `iss`、`iat`、`exp`、`jti` 与 header `kid`。破坏性变更仅限内部结构，对外 API 响应不变。
6. **库升级**：jjwt 0.9.1 → jjwt 0.12.x（保留 JwtUtils 代码结构，改动签名/解析调用），移除 jaxb-api 兜底。备选（记录不改）：spring-security-oauth2-resource-server + nimbus，需重写 SecurityConfig，收益不足以抵消 Phase 2 风险。
7. **内部调用身份传递**：Feign `RequestInterceptor` 透传原始 `Authorization` + `traceparent`；服务一律本地验签，不信任任何明文内部头。Gateway 在入口强制覆盖/剥离客户端伪造的 `X-User-Id` 类内部头；内部头仅作为网关验证后的加速上下文，不作为鉴权依据。
8. **登出**：保持现状（客户端删除 token）；因 token 无服务端状态，暂不建黑名单，如需强制失效再引入 `jti` 黑名单缓存（Identity 命名空间）。

## 备选方案

- 继续对称密钥 + Nacos 分发：被否决，密钥扩散面等于服务数，违背最小密钥持有原则。
- 每请求远程鉴权（Identity 中心化校验）：被否决，与 design.md"本地验签减少每请求远程鉴权"冲突，Identity 成为热点与单点。

## 后果

- Phase 2 需实现：Identity 的密钥管理（生成、存储、`kid` 轮换）、`/jwks` 端点、Gateway 全局过滤器、各服务验签 starter（公共模块提供）。
- E2E 必须覆盖：登录/过期/刷新、伪造 token 401、越权 403、JWKS 轮换后旧 token 失效窗口。
