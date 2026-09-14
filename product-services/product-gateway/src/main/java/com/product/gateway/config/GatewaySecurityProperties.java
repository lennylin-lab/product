package com.product.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 网关认证与安全前置配置（ADR-0003：网关两层校验的网关侧）。
 */
@ConfigurationProperties(prefix = "product.gateway")
public class GatewaySecurityProperties {

    /** JWT 请求头名称（与单体 token.header 一致） */
    private String header = "Authorization";

    /** Identity JWKS 端点 */
    private String jwksUri = "http://127.0.0.1:8101/jwks";

    /** 是否启用 JWT 前置校验（默认开启） */
    private boolean authEnabled = true;

    /** 验签时钟偏移容忍（秒） */
    private long clockSkewSeconds = 60;

    /**
     * 匿名放行端点（Ant 风格）。对照单体 SecurityConfig permitAll：
     * /login、/register（冻结为不存在）、/captchaImage；静态资源与 /druid/** 属单体进程内资产，
     * 网关不代理；新增 /jwks（公钥发布）与网关自身运维端点。
     */
    private List<String> permitPaths = new ArrayList<>(List.of(
            "/login", "/register", "/captchaImage", "/jwks",
            "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**", "/v3/api-docs.yaml",
            "/actuator/**"));

    /**
     * 客户端可伪造、入口必须剥离/覆盖的内部头（ADR-0003 决策 7：内部头不作为鉴权依据）。
     */
    private List<String> stripHeaders = new ArrayList<>(List.of(
            "X-User-Id", "X-User-Name", "X-User-Account", "X-User-Permissions", "X-Internal-Client"));

    public String getHeader() {
        return header;
    }

    public void setHeader(String header) {
        this.header = header;
    }

    public String getJwksUri() {
        return jwksUri;
    }

    public void setJwksUri(String jwksUri) {
        this.jwksUri = jwksUri;
    }

    public boolean isAuthEnabled() {
        return authEnabled;
    }

    public long getClockSkewSeconds() {
        return clockSkewSeconds;
    }

    public void setClockSkewSeconds(long clockSkewSeconds) {
        this.clockSkewSeconds = clockSkewSeconds;
    }

    public void setAuthEnabled(boolean authEnabled) {
        this.authEnabled = authEnabled;
    }

    public List<String> getPermitPaths() {
        return permitPaths;
    }

    public void setPermitPaths(List<String> permitPaths) {
        this.permitPaths = permitPaths;
    }

    public List<String> getStripHeaders() {
        return stripHeaders;
    }

    public void setStripHeaders(List<String> stripHeaders) {
        this.stripHeaders = stripHeaders;
    }
}
