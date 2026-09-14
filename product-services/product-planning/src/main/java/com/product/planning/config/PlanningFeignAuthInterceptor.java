package com.product.planning.config;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * planning Feign 鉴权拦截器（Phase 4；与 demand/master-data 的 FeignForwardAuthConfig
 * 同基线 + 服务身份兜底）。
 *
 * <p>优先级：</p>
 * <ol>
 *   <li>请求线程内调用（如订单行详情填充、删行级联——demand→planning；删除保护链——
 *       master-data→demand/planning）：透传调用方 {@code Authorization}（ADR-0003 决策 7，
 *       提供方本地验签，不注入任何明文身份头）；</li>
 *   <li>无请求上下文的异步调用（排程任务后台线程加载需求/主数据快照）：携带 Identity
 *       签发的服务身份令牌（{@link ServiceIdentityTokenProvider}，ADR-0003 服务身份
 *       最小实现）。</li>
 * </ol>
 * <p>重试：OpenFeign 默认 {@code Retryer.NEVER_RETRY}，维持默认；超时见 application.yml
 * {@code spring.cloud.openfeign.client.config.*}。</p>
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class PlanningFeignAuthInterceptor {

    private final ServiceIdentityTokenProvider tokenProvider;

    @Bean
    public RequestInterceptor planningAuthorizationInterceptor() {
        return template -> {
            String userToken = currentRequestAuthorization();
            if (userToken != null) {
                template.header("Authorization", userToken);
                return;
            }
            template.header("Authorization", "Bearer " + tokenProvider.getToken());
        };
    }

    private String currentRequestAuthorization() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            String authorization = request.getHeader("Authorization");
            if (authorization != null && !authorization.isEmpty()) {
                return authorization;
            }
        }
        return null;
    }

    /**
     * planning 的全部 Feign 客户端均为内部契约（demand/master-data 的 /internal/**），
     * 故无用户上下文时一律使用服务身份。注意不能用 RequestTemplate.path() 判定：
     * 该值不含 @FeignClient(path=...) 的客户端级前缀（形如 /order-lines/batch），
     * 以 "/internal/" 前缀判断会漏判（实测踩坑：异步排程快照调用 401-in-200）。
     */
}
