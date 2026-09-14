package com.product.cloud.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.UUID;

/**
 * 请求上下文基线过滤器（微服务体系）。
 *
 * <p>行为与现有单体 {@code product-auth} 的 {@code TraceMdcFilter} 对齐：</p>
 * <ul>
 *   <li>MDC 注入 {@code traceId}/{@code requestId}/{@code clientIp}/{@code httpMethod}/{@code requestUri}，
 *       与共享 logback 基线（logback/product-cloud-base.xml）及 ELK JSON 字段一一对应；</li>
 *   <li>traceId 解析优先级：{@code X-Trace-Id} 头（网关/上游传入）→ W3C {@code traceparent}
 *       的 trace-id（Micrometer Tracing 同源）→ 本地生成 UUID；</li>
 *   <li>响应头回写 {@code X-Trace-Id}/{@code X-Request-Id}，供客户端与排障关联；</li>
 *   <li>请求结束清理 MDC，避免线程污染。</li>
 * </ul>
 *
 * <p>traceId/traceparent 合流：当请求缺少 W3C {@code traceparent} 时，按解析出的 traceId
 * 合成 {@code 00-<trace-id>-<parent-span-id>-01} 并通过请求包装器注入下游（滤镜链内的
 * Boot/Micrometer server span 因此继承同一 traceId）。否则 Micrometer 的日志关联会在 span
 * 作用域内用新 traceId 覆盖 MDC，导致 {@code X-Trace-Id} 与日志/span 的 traceId 分叉
 * （Phase 1 实测问题；网关侧依赖 SCG 原生 Micrometer 传播，勿再注入 trace 头）。</p>
 *
 * <p>注册顺序为最高优先级，保证业务链路与后续安全过滤器都在其覆盖范围内。</p>
 */
public class RequestContextFilter extends OncePerRequestFilter implements Ordered {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final String TRACEPARENT_HEADER = "traceparent";

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String traceId = resolveOrGenerateId(request.getHeader(TRACE_ID_HEADER), extractTraceparent(request.getHeader(TRACEPARENT_HEADER)));
        String requestId = resolveOrGenerateId(request.getHeader(REQUEST_ID_HEADER), null);
        try {
            MDC.put("traceId", traceId);
            MDC.put("requestId", requestId);
            MDC.put("clientIp", request.getRemoteAddr());
            MDC.put("httpMethod", request.getMethod());
            MDC.put("requestUri", request.getRequestURI());
            response.setHeader(TRACE_ID_HEADER, traceId);
            response.setHeader(REQUEST_ID_HEADER, requestId);
            filterChain.doFilter(wrapWithTraceparent(request, traceId), response);
        } finally {
            MDC.clear();
        }
    }

    /**
     * 缺少 traceparent 时按 traceId 合成一个，保证链路内 Micrometer server span 使用同一 traceId。
     */
    private HttpServletRequest wrapWithTraceparent(HttpServletRequest request, String traceId) {
        if (request.getHeader(TRACEPARENT_HEADER) != null && !request.getHeader(TRACEPARENT_HEADER).isBlank()) {
            return request;
        }
        final String traceparent = "00-" + traceId + "-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 16) + "-01";
        final List<String> originalNames = Collections.list(request.getHeaderNames());
        if (originalNames.stream().noneMatch(TRACEPARENT_HEADER::equalsIgnoreCase)) {
            originalNames.add(TRACEPARENT_HEADER);
        }
        final List<String> headerNames = originalNames;
        return new HttpServletRequestWrapper(request) {
            @Override
            public String getHeader(String name) {
                if (TRACEPARENT_HEADER.equalsIgnoreCase(name)) {
                    return traceparent;
                }
                return super.getHeader(name);
            }

            @Override
            public Enumeration<String> getHeaders(String name) {
                if (TRACEPARENT_HEADER.equalsIgnoreCase(name)) {
                    return Collections.enumeration(List.of(traceparent));
                }
                return super.getHeaders(name);
            }

            @Override
            public Enumeration<String> getHeaderNames() {
                return Collections.enumeration(headerNames);
            }
        };
    }

    /**
     * 从 W3C traceparent 头提取 trace-id（32 位十六进制）。
     * 格式：{@code 00-<trace-id>-<parent-span-id>-<flags>}。
     * 与 Micrometer Tracing（W3C 传播）生成的 traceId 同值，保证日志与链路追踪可关联。
     */
    private String extractTraceparent(String traceparent) {
        if (traceparent == null || traceparent.isBlank()) {
            return null;
        }
        String[] parts = traceparent.trim().split("-");
        if (parts.length >= 3 && parts[1].length() == 32) {
            return parts[1];
        }
        return null;
    }

    private String resolveOrGenerateId(String headerValue, String fallback) {
        if (headerValue != null && !headerValue.isBlank()) {
            return headerValue.trim();
        }
        if (fallback != null && !fallback.isBlank()) {
            return fallback;
        }
        return UUID.randomUUID().toString().replace("-", "");
    }
}
