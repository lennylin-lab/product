package com.product.planning.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 服务身份令牌属性（Phase 4，ADR-0003 服务身份最小实现）：
 * 异步排程线程无用户请求上下文时，经 Identity 内部端点
 * {@code POST /internal/identity/service-token} 以服务凭据换取 RS256 token。
 * 凭据经环境注入（默认值仅限本地开发，见 .env.example）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "product.service-identity")
public class ServiceIdentityProperties {

    /** 是否启用服务身份兜底（关闭后无请求上下文的 Feign 调用不携带 Authorization）。 */
    private boolean enabled = true;

    /** Identity 内部服务令牌端点（直连 identity 端口；网关对 /internal/** 显式 404）。 */
    private String tokenUrl = "http://127.0.0.1:8101/internal/identity/service-token";

    /** 服务账号（identity_db 种子 planning_svc）。 */
    private String username = "planning_svc";

    /** 服务账号密码（生产经环境注入）。 */
    private String password = "planning-svc-dev-pwd";

    /** token 提前失效余量（毫秒）：缓存至过期前该余量自动重签。 */
    private long expireSkewMillis = 60_000L;
}
