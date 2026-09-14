package com.product.cloud.security.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端本地验签配置（ADR-0003）。
 */
@ConfigurationProperties(prefix = "product.security")
public class ProductSecurityProperties {

    /** 是否启用本地验签安全链；默认开启（安全缺省）。离线单测可显式关闭。 */
    private boolean enabled = true;

    /** 请求头名称（与单体 token.header 一致）。 */
    private String header = "Authorization";

    /** Identity 的 JWKS 端点地址；网关与服务据此拉取/缓存公钥。 */
    private String jwksUri = "http://127.0.0.1:8101/jwks";

    /** 验签时钟偏移容忍（秒）。 */
    private long clockSkewSeconds = 60;

    /**
     * 额外放行的匿名 URL（Ant 风格）。基线匿名端点（/login、/captchaImage、/jwks、swagger、
     * actuator 健康端点）已内建，无需重复配置。
     */
    private List<String> permitUrls = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

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

    public long getClockSkewSeconds() {
        return clockSkewSeconds;
    }

    public void setClockSkewSeconds(long clockSkewSeconds) {
        this.clockSkewSeconds = clockSkewSeconds;
    }

    public List<String> getPermitUrls() {
        return permitUrls;
    }

    public void setPermitUrls(List<String> permitUrls) {
        this.permitUrls = permitUrls;
    }
}
