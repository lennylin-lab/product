package com.product.gateway.web;

import io.micrometer.tracing.Tracer;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * 内部契约端点显式拒绝过滤器（Phase 4 必修项，Phase 3 check 遗留）。
 *
 * <p>背景：Phase 1 的骨架冒烟前缀路由（/master-data/**、/demand-svc/**、/planning/**、
 * /execution/**，StripPrefix=1）会把 {@code <前缀>/internal/**} 转发到服务内部的
 * {@code /internal/**} 契约端点（例如 /master-data/internal/master-data/products/exists）。
 * 这些端点虽仍要求有效 JWT（只读、非鉴权绕过），但违背"内部契约端点不对外暴露"的边界
 * （ADR-0002 决策 1：内部契约仅限服务间调用）。</p>
 *
 * <p>规则：请求路径中任意路径段等于 {@code internal}（含字面 {@code /internal/**} 与
 * 任意 {@code <前缀>/internal/**}）一律在网关短路拒绝，返回统一 404 错误体
 * {@code {"msg":"请求路径不存在","code":404}} + X-Trace-Id（与网关未路由 404 同一错误契约）。
 * 外部 API 基线（baselines.md §1.2）无任何路径含 internal 段，故该拒绝不影响外部语义；
 * 服务间 Feign 调用不经网关（Nacos 服务发现直连），不受影响。</p>
 */
public class InternalPathDenyFilter implements GlobalFilter, Ordered {

    /** 需要拒绝的内部路径段（完整匹配一个 segment）。 */
    static final String INTERNAL_SEGMENT = "internal";

    private final Tracer tracer;

    public InternalPathDenyFilter(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // path.value() 为未解码原始路径：containsInternalSegment 内部逐段先剥矩阵参数
        // 再 URL 解码，防 %69nternal / internal;x 类变形绕过
        if (containsInternalSegment(exchange.getRequest().getPath().value())) {
            ServerHttpResponse response = exchange.getResponse();
            response.setStatusCode(HttpStatus.NOT_FOUND);
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            response.getHeaders().set(TraceContextSupport.TRACE_HEADER,
                    TraceContextSupport.resolveTraceId(exchange, tracer));
            byte[] bytes = GatewayErrorBodies.notFound().getBytes(StandardCharsets.UTF_8);
            return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
        }
        return chain.filter(exchange);
    }

    /**
     * 判定请求路径是否包含 internal 段（按 '/' 分段完整匹配，避免误伤
     * /internalX 之类的前缀路径）。
     *
     * <p>path.value() 为未解码原始路径，故对每段依次：① 剥离矩阵参数
     * （{@code internal;x} → {@code internal}，路由转发后由各框架按段解析）；
     * ② URL 解码（{@code %69nternal} → {@code internal}）。解码失败（畸形转义）
     * 保留原段继续匹配，不因畸形输入放弃拒绝。</p>
     */
    public static boolean containsInternalSegment(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        for (String rawSegment : path.split("/")) {
            if (INTERNAL_SEGMENT.equals(stripMatrixParams(urlDecode(rawSegment)))) {
                return true;
            }
        }
        return false;
    }

    /** 剥离单段内的矩阵参数（第一个 ';' 起全部去除）。 */
    static String stripMatrixParams(String segment) {
        int cut = segment.indexOf(';');
        return cut >= 0 ? segment.substring(0, cut) : segment;
    }

    /** URL 解码单段（UTF-8）；畸形转义时返回原文（继续参与匹配，不放弃拒绝）。 */
    static String urlDecode(String segment) {
        try {
            return java.net.URLDecoder.decode(segment, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return segment;
        }
    }

    @Override
    public int getOrder() {
        // 先于所有业务/认证/剥离过滤器短路，内部路径不允许产生任何下游副作用
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
