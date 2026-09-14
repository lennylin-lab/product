package com.product.gateway.web;

import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.cloud.gateway.support.NotFoundException;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * 网关统一错误响应处理器（Phase 1 遗留项：非代理错误响应统一错误体 + X-Trace-Id）。
 *
 * <p>覆盖：路由无实例（LoadBalancer NotFoundException → 503）、未匹配路由（404）、
 * 其它网关自身异常。全部输出与 product-cloud-common 错误契约一致的
 * {msg, code} JSON 信封，并回写 X-Trace-Id 响应头，保证排障时网关错误可关联日志与链路。</p>
 */
public class GatewayErrorWebExceptionHandler implements ErrorWebExceptionHandler, org.springframework.core.Ordered {

    private static final Logger log = LoggerFactory.getLogger(GatewayErrorWebExceptionHandler.class);

    private final Tracer tracer;

    public GatewayErrorWebExceptionHandler(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.error(ex);
        }
        int code = resolveCode(ex, response);
        log.warn("网关统一错误响应: path={}, code={}, reason={}",
                exchange.getRequest().getURI().getPath(), code, ex.getMessage());

        response.setStatusCode(HttpStatus.resolve(code) != null ? HttpStatus.valueOf(code) : HttpStatus.INTERNAL_SERVER_ERROR);
        response.getHeaders().setContentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8));
        response.getHeaders().set(TraceContextSupport.TRACE_HEADER, TraceContextSupport.resolveTraceId(exchange, tracer));
        byte[] bytes = bodyFor(code).getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }

    private int resolveCode(Throwable ex, ServerHttpResponse response) {
        if (ex instanceof NotFoundException || ex.getCause() instanceof NotFoundException) {
            // 路由有匹配但注册中心无可用实例（LoadBalancer NotFoundException 默认映射 503）
            return 503;
        }
        if (ex instanceof ResponseStatusException responseStatusException) {
            return responseStatusException.getStatusCode().value();
        }
        if (response.getStatusCode() != null && response.getStatusCode().isError()) {
            return response.getStatusCode().value();
        }
        return 500;
    }

    private String bodyFor(int code) {
        return switch (code) {
            case 404 -> GatewayErrorBodies.notFound();
            case 503 -> GatewayErrorBodies.serviceUnavailable();
            case 401 -> GatewayErrorBodies.unauthorized("");
            case 429 -> GatewayErrorBodies.tooManyRequests();
            default -> GatewayErrorBodies.internalError();
        };
    }

    @Override
    public int getOrder() {
        // 优先于 Boot 默认 ErrorWebExceptionHandler（order = -1）
        return -2;
    }
}
