package com.product.execution.config;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * execution Feign 鉴权拦截器（与 demand 的 FeignForwardAuthConfig 同基线）。
 *
 * <p>调用 planning 只读契约（任务运行时）时透传当前用户的 {@code Authorization:
 * Bearer <token>}，由 planning 本地验签；不注入任何明文身份头（ADR-0003）。本服务
 * 的跨域调用全部由用户命令触发（/execute/event），无异步线程调用场景；无请求上下文
 * 时不携带 token——调用将被 planning 401 拒绝并按契约 fail-closed（命令返回失败）。</p>
 *
 * <p>重试：OpenFeign 默认 {@code Retryer.NEVER_RETRY}，维持默认（任务事件登记不得
 * 自动重试）；超时见 application.yml {@code spring.cloud.openfeign.client.config.*}。</p>
 */
@Slf4j
@Configuration
public class ExecutionFeignAuthConfig {

    @Bean
    public RequestInterceptor executionAuthorizationForwardInterceptor() {
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
