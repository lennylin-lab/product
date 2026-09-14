package com.product.gateway.web;

import com.product.gateway.config.GatewaySecurityProperties;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 内部身份头剥离过滤器（ADR-0003 决策 7 的防伪约束）。
 *
 * <p>网关在入口强制剥离客户端伪造的 X-User-Id / X-User-Name / X-User-Permissions 类
 * 明文内部头；验证通过后也不注入替代头——服务一律本地验签 token 重建身份，
 * 明文内部头不作为鉴权依据。</p>
 */
public class InternalHeaderSanitizerFilter implements GlobalFilter, Ordered {

    private final GatewaySecurityProperties properties;

    public InternalHeaderSanitizerFilter(GatewaySecurityProperties properties) {
        this.properties = properties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest mutated = exchange.getRequest().mutate().headers(headers -> {
            for (String header : properties.getStripHeaders()) {
                headers.remove(header);
            }
        }).build();
        return chain.filter(exchange.mutate().request(mutated).build());
    }

    @Override
    public int getOrder() {
        // 在所有业务/认证过滤器之前完成剥离
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }
}
