package com.product.gateway.web;

import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;

import java.util.UUID;

/**
 * traceId 解析辅助：优先请求头 X-Trace-Id，其次 W3C traceparent 的 trace-id
 * （SCG 原生 Micrometer 传播），再次当前 span 上下文（若 Micrometer Tracer 已生效），
 * 否则本地生成。与 RequestContextFilter 的服务端规则一致。
 */
public final class TraceContextSupport {

    public static final String TRACE_HEADER = "X-Trace-Id";
    private static final String TRACEPARENT_HEADER = "traceparent";

    private TraceContextSupport() {
    }

    public static String resolveTraceId(ServerWebExchange exchange, io.micrometer.tracing.Tracer tracer) {
        ServerHttpRequest request = exchange.getRequest();
        String explicit = request.getHeaders().getFirst(TRACE_HEADER);
        if (explicit != null && !explicit.isBlank()) {
            return explicit.trim();
        }
        String fromParent = extractTraceId(request.getHeaders().getFirst(TRACEPARENT_HEADER));
        if (fromParent != null) {
            return fromParent;
        }
        if (tracer != null) {
            var currentSpan = tracer.currentSpan();
            if (currentSpan != null) {
                String traceId = currentSpan.context().traceId();
                if (traceId != null && !traceId.isBlank()) {
                    return traceId;
                }
            }
        }
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String extractTraceId(String traceparent) {
        if (traceparent == null || traceparent.isBlank()) {
            return null;
        }
        String[] parts = traceparent.trim().split("-");
        if (parts.length >= 3 && parts[1].length() == 32) {
            return parts[1];
        }
        return null;
    }
}
