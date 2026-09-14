package com.product.gateway;

import com.product.gateway.web.InternalPathDenyFilter;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内部契约端点显式拒绝契约（Phase 4 必修项）：路径含 internal 段的请求必须在网关短路，
 * 返回统一 404 错误体（与网关未路由 404 逐字节一致）+ X-Trace-Id，链路不得继续。
 */
class InternalPathDenyFilterTest {

    private final InternalPathDenyFilter filter = new InternalPathDenyFilter(null);

    @Test
    void literalInternalPathShouldBeDeniedWithUnified404Body() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/internal/planning/batches/by-order-lines")
                        .header("X-Trace-Id", "trace-deny-1").build());
        AtomicBoolean invoked = new AtomicBoolean(false);
        filter.filter(exchange, exchange1 -> {
            invoked.set(true);
            return Mono.empty();
        }).block();

        assertFalse(invoked.get(), "chain must not be invoked for /internal/**");
        assertEquals(HttpStatus.NOT_FOUND, exchange.getResponse().getStatusCode());
        assertEquals("{\"msg\":\"请求路径不存在\",\"code\":404}",
                exchange.getResponse().getBodyAsString().block());
        assertEquals("trace-deny-1", exchange.getResponse().getHeaders().getFirst("X-Trace-Id"));
    }

    @Test
    void smokePrefixRouteLeakShouldBeDenied() {
        // Phase 3 check 发现的穿透形态：冒烟前缀路由 StripPrefix=1 后可命中服务内部契约端点
        for (String path : new String[]{
                "/master-data/internal/master-data/products/exists",
                "/demand-svc/internal/demand/order-lines/batch",
                "/planning/internal/planning/batches/has-blocking-tasks",
                "/execution/internal/execute/anything"}) {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.post(path).build());
            AtomicBoolean invoked = new AtomicBoolean(false);
            filter.filter(exchange, exchange1 -> {
                invoked.set(true);
                return Mono.empty();
            }).block();
            assertFalse(invoked.get(), "chain must not be invoked for " + path);
            assertEquals(HttpStatus.NOT_FOUND, exchange.getResponse().getStatusCode(), path);
        }
    }

    @Test
    void segmentMatchMustNotTouchSimilarPrefixes() {
        assertFalse(InternalPathDenyFilter.containsInternalSegment("/pps/batch/list"));
        assertFalse(InternalPathDenyFilter.containsInternalSegment("/demand/orderLine/1"));
        assertFalse(InternalPathDenyFilter.containsInternalSegment("/internals/other"));
        assertFalse(InternalPathDenyFilter.containsInternalSegment("/internalx"));
        assertTrue(InternalPathDenyFilter.containsInternalSegment("/internal/"));
        assertTrue(InternalPathDenyFilter.containsInternalSegment("/a/internal/b"));
    }

    @Test
    void encodedAndMatrixVariantsMustBeDenied() {
        // URL 编码与矩阵参数变形不得绕过（decode/matrix-strip 加固）。
        // 注意用 URI 重载构造请求：字符串重载会把 '%' 再编码为 %25，失去原始未解码路径语义。
        for (java.net.URI uri : java.util.List.of(
                java.net.URI.create("/master-data/internal/master-data/products/exists"),
                java.net.URI.create("/planning/%69nternal/planning/batches/has-blocking-tasks"),
                java.net.URI.create("/planning/internal;x/planning/batches/has-blocking-tasks"),
                java.net.URI.create("/a/%69nternal;x/b"))) {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.method(org.springframework.http.HttpMethod.GET, uri).build());
            String path = uri.getRawPath();
            AtomicBoolean invoked = new AtomicBoolean(false);
            filter.filter(exchange, exchange1 -> {
                invoked.set(true);
                return Mono.empty();
            }).block();
            assertFalse(invoked.get(), "variant must be denied: " + path);
            assertEquals(HttpStatus.NOT_FOUND, exchange.getResponse().getStatusCode(), path);
        }
    }

    @Test
    void encodedVariantsDeniedEndToEndViaRawPath() {
        // 与生产一致的原始路径入口：直接断言 containsInternalSegment 对未解码路径返回 true
        org.springframework.mock.http.server.reactive.MockServerHttpRequest req =
                org.springframework.mock.http.server.reactive.MockServerHttpRequest
                        .method(org.springframework.http.HttpMethod.GET,
                                java.net.URI.create("/planning/%69nternal/planning/batches/has-blocking-tasks")).build();
        assertTrue(InternalPathDenyFilter.containsInternalSegment(req.getPath().value()),
                "raw undecoded path must resolve to an internal segment");
    }

    @Test
    void malformedEncodingMustNotBypassDenyOrBreakMatching() {
        // 畸形转义保留原文参与匹配：/%zz 不等于 internal（放行），/%69nternal 解码后仍拒绝
        assertFalse(InternalPathDenyFilter.containsInternalSegment("/%zz/other"));
        assertTrue(InternalPathDenyFilter.containsInternalSegment("/%69nternal/x"));
    }

    @Test
    void businessPathsShouldPassThrough() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/pps/batch/list").build());
        AtomicBoolean invoked = new AtomicBoolean(false);
        filter.filter(exchange, exchange1 -> {
            invoked.set(true);
            return Mono.empty();
        }).block();
        assertTrue(invoked.get(), "business path must continue the chain");
    }
}
