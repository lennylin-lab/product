package com.product.demand.config;

import feign.RequestInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Feign 调用配置（design.md §4：内部调用传递用户上下文，不伪造内部身份头）。
 *
 * <p>鉴权模型（ADR-0003 两层校验）：demand 调用 master-data 时透传当前用户的
 * {@code Authorization: Bearer <token>}，由 master-data 本地验签；demand 不注入任何
 * 明文身份头（X-User-* 在网关入口即被剥离，服务间同样禁止伪造）。无请求上下文
 * （如异步线程）时不携带 token —— 调用将被 master-data 401 拒绝并按契约 fail-closed；
 * 服务身份令牌（client credentials）随 Phase 4 排程任务引入时补充。</p>
 *
 * <p>重试：Spring Cloud OpenFeign 默认 {@code Retryer.NEVER_RETRY}，本服务维持默认
 * （契约 javadoc：写路径前置校验调用不得自动重试）；超时见 application.yml
 * {@code spring.cloud.openfeign.client.config.masterDataBatchQueryClient}。</p>
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
