package com.product.identity.auth.controller;

import com.product.identity.auth.service.JwtKeyManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * JWKS 公钥发布端点（ADR-0003 决策 3）：{@code GET /jwks}。
 *
 * <p>匿名可访问（安全链内建放行）；网关与各服务按 token header {@code kid} 匹配公钥
 * 本地验签，不调用本端点做每请求鉴权。</p>
 */
@RestController
public class JwksController {

    private final JwtKeyManager keyManager;

    public JwksController(JwtKeyManager keyManager) {
        this.keyManager = keyManager;
    }

    @GetMapping("/jwks")
    public Map<String, Object> jwks() {
        return keyManager.jwks();
    }
}
