package com.product.gateway.web;

import com.product.gateway.config.GatewaySecurityProperties;
import com.product.cloud.security.jwks.JwksKeyHolder;
import com.product.cloud.security.jwt.JwtVerificationException;
import com.product.cloud.security.jwt.JwtVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 网关 JWT 前置校验全局过滤器（ADR-0003 决策 4：两层校验的网关侧）。
 *
 * <ul>
 *   <li>匿名端点（permitPaths，对照单体 SecurityConfig permitAll）直接放行；</li>
 *   <li>其余请求必须携带可本地验签的 Bearer token（RS256 + exp，JWKS 按 kid 取钥）；</li>
 *   <li>验签失败返回与单体逐字节一致的 401 错误体（HTTP 200 + code 401 JSON）并回写
 *       X-Trace-Id（Phase 1 遗留的网关非代理错误响应统一化的一部分）；</li>
 *   <li>本过滤器只做“拒绝非法”，验证通过后不注入任何明文内部身份头（防伪约束：
 *       服务一律本地验签，不信任明文内部头）。</li>
 * </ul>
 */
public class JwtAuthGlobalFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthGlobalFilter.class);
    private static final String TOKEN_PREFIX = "Bearer ";
    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    private final GatewaySecurityProperties properties;
    private final JwtVerifier jwtVerifier;
    private final JwksKeyHolder keyHolder;
    private final io.micrometer.tracing.Tracer tracer;

    public JwtAuthGlobalFilter(GatewaySecurityProperties properties, JwtVerifier jwtVerifier,
                               JwksKeyHolder keyHolder, io.micrometer.tracing.Tracer tracer) {
        this.properties = properties;
        this.jwtVerifier = jwtVerifier;
        this.keyHolder = keyHolder;
        this.tracer = tracer;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();
        if (isPermitted(path)) {
            return chain.filter(exchange);
        }
        if (!properties.isAuthEnabled()) {
            return chain.filter(exchange);
        }
        String token = extractToken(request);
        if (!StringUtils.hasText(token)) {
            return reject(exchange);
        }
        // JWKS 拉取为阻塞 IO，放到 boundedElastic 防止占用 Reactor 事件循环
        return Mono.fromCallable(() -> verify(token))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(valid -> valid ? chain.filter(exchange) : reject(exchange));
    }

    private boolean verify(String token) {
        String kid = headerKid(token);
        var publicKey = keyHolder.forKeyId(kid);
        if (publicKey == null) {
            log.debug("JWT 前置校验：无法取得验签公钥（JWKS 不可达或未知 kid）");
            return false;
        }
        try {
            var verified = jwtVerifier.verify(token, publicKey);
            jwtVerifier.verifyExpireClaim(verified.claims());
            return true;
        } catch (JwtVerificationException e) {
            log.debug("JWT 前置校验失败：{}", e.getMessage());
            return false;
        }
    }

    private Mono<Void> reject(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.OK);
        // 与单体 ServletUtils.renderString 一致：HTTP 200 + JSON 错误体（HTTP 层不暴露 401）
        response.getHeaders().setContentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8));
        // tracer 在构造器注入（见 GatewayConfiguration）
        response.getHeaders().set(TraceContextSupport.TRACE_HEADER, TraceContextSupport.resolveTraceId(exchange, tracer));
        byte[] body = GatewayErrorBodies.unauthorized(exchange.getRequest().getURI().getPath())
                .getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }

    private boolean isPermitted(String path) {
        List<String> patterns = properties.getPermitPaths();
        for (String pattern : patterns) {
            if (PATH_MATCHER.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    private String extractToken(ServerHttpRequest request) {
        String token = request.getHeaders().getFirst(properties.getHeader());
        if (StringUtils.hasText(token) && token.startsWith(TOKEN_PREFIX)) {
            return token.substring(TOKEN_PREFIX.length());
        }
        return token;
    }

    /** 未验签前仅解析 header 的 kid（用于选钥）。 */
    private String headerKid(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                return null;
            }
            String headerJson = new String(
                    com.product.cloud.security.jwt.Jwks.decodeBase64Url(parts[0]), StandardCharsets.UTF_8);
            if (headerJson.contains("\"kid\"")) {
                return new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(headerJson).path("kid").asText(null);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public int getOrder() {
        // Sentinel 网关过滤器（更低 order）先计数限流；本过滤器紧随其后做认证拒绝
        return -10;
    }
}
