package com.product.gateway.config;

import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayFlowRule;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayRuleManager;
import com.alibaba.csp.sentinel.adapter.gateway.sc.callback.GatewayCallbackManager;
import com.product.cloud.security.jwks.JwksKeyHolder;
import com.product.cloud.security.jwt.JwtVerifier;
import com.product.gateway.web.GatewayErrorBodies;
import com.product.gateway.web.GatewayErrorWebExceptionHandler;
import com.product.gateway.web.InternalHeaderSanitizerFilter;
import com.product.gateway.web.JwtAuthGlobalFilter;
import com.product.gateway.web.TraceContextSupport;
import io.micrometer.tracing.Tracer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import jakarta.annotation.PostConstruct;

/**
 * 网关 Phase 2 装配：JWT 前置校验、内部头剥离、统一错误响应、Sentinel 网关限流。
 */
@Configuration
@EnableConfigurationProperties({GatewaySecurityProperties.class, GatewayConfiguration.GatewaySentinelProperties.class})
public class GatewayConfiguration {

    private final Tracer tracer;
    private final GatewaySentinelProperties sentinelProperties;

    public GatewayConfiguration(Tracer tracer, GatewaySentinelProperties sentinelProperties) {
        this.tracer = tracer;
        this.sentinelProperties = sentinelProperties;
    }

    @Bean
    public JwtVerifier gatewayJwtVerifier(GatewaySecurityProperties properties) {
        return new JwtVerifier(properties.getClockSkewSeconds());
    }

    @Bean
    public JwksKeyHolder gatewayJwksKeyHolder(GatewaySecurityProperties properties) {
        return new JwksKeyHolder(properties.getJwksUri());
    }

    @Bean
    public JwtAuthGlobalFilter jwtAuthGlobalFilter(GatewaySecurityProperties properties,
                                                    JwtVerifier jwtVerifier,
                                                    JwksKeyHolder keyHolder) {
        return new JwtAuthGlobalFilter(properties, jwtVerifier, keyHolder, tracer);
    }

    @Bean
    public InternalHeaderSanitizerFilter internalHeaderSanitizerFilter(GatewaySecurityProperties properties) {
        return new InternalHeaderSanitizerFilter(properties);
    }

    @Bean
    public GatewayErrorWebExceptionHandler gatewayErrorWebExceptionHandler() {
        return new GatewayErrorWebExceptionHandler(tracer);
    }

    /**
     * Sentinel 网关流控：规则在网关启动时从属性装载（routeId + QPS 阈值 + 限流响应统一错误体）。
     * 规则持久化到 Nacos（sentinel-datasource）随 Phase 3+ 引入；当前为应用内存规则（进程级）。
     */
    @PostConstruct
    public void initSentinelGatewayRules() {
        // 限流触发时返回统一错误体 + X-Trace-Id（与网关错误契约一致，baselines.md §3.3-14）
        GatewayCallbackManager.setBlockHandler(this::renderBlockedResponse);
        Set<GatewayFlowRule> rules = new HashSet<>();
        sentinelProperties.getRoutes().forEach((routeId, qps) -> {
            GatewayFlowRule rule = new GatewayFlowRule(routeId);
            rule.setGrade(com.alibaba.csp.sentinel.slots.block.RuleConstant.FLOW_GRADE_QPS);
            rule.setCount(qps);
            rules.add(rule);
        });
        if (!rules.isEmpty()) {
            GatewayRuleManager.loadRules(rules);
        }
    }

    private Mono<org.springframework.web.reactive.function.server.ServerResponse> renderBlockedResponse(
            ServerWebExchange exchange, Throwable throwable) {
        exchange.getResponse().getHeaders().set(TraceContextSupport.TRACE_HEADER,
                TraceContextSupport.resolveTraceId(exchange, tracer));
        byte[] bytes = GatewayErrorBodies.tooManyRequests().getBytes(StandardCharsets.UTF_8);
        return org.springframework.web.reactive.function.server.ServerResponse
                .status(HttpStatus.TOO_MANY_REQUESTS)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(bytes);
    }

    /**
     * Sentinel 网关规则属性：routeId -> QPS。
     */
    @ConfigurationProperties(prefix = "product.gateway.sentinel")
    public static class GatewaySentinelProperties {

        /** 每路由 QPS 阈值（默认 200；live 验证时以环境变量调低以触发限流） */
        private java.util.Map<String, Double> routes = new java.util.HashMap<>();

        public java.util.Map<String, Double> getRoutes() {
            return routes;
        }

        public void setRoutes(java.util.Map<String, Double> routes) {
            this.routes = routes;
        }
    }
}
