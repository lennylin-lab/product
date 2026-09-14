package com.product.masterdata.config;

import feign.RequestInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Feign 调用配置（Phase 4；与 product-demand 的 FeignForwardAuthConfig 同一基线）。
 *
 * <p>master-data 调用 demand/planning 的内部契约（启用工艺路线删除保护链路）时
 * 透传当前用户的 {@code Authorization: Bearer <token>}，由提供方本地验签（ADR-0003
 * 两层校验）；不注入任何明文身份头。无请求上下文时不携带 token——调用将被提供方 401
 * 拒绝并按契约 fail-closed（本服务全部跨域调用都发生在用户请求线程内）。</p>
 *
 * <p>重试：OpenFeign 默认 {@code Retryer.NEVER_RETRY}，维持默认；超时见 application.yml
 * {@code spring.cloud.openfeign.client.config.*}。</p>
 */
@Slf4j
@Configuration
public class FeignForwardAuthConfig {

    @Bean
    public RequestInterceptor authorizationForwardInterceptor() {
        return template -> {
            if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
                HttpServletRequest request = attributes.getRequest();
                String authorization = request.getHeader("Authorization");
                if (authorization != null && !authorization.isEmpty()) {
                    template.header("Authorization", authorization);
                }
            }
        };
    }
}
