package com.product.cloud.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 请求上下文过滤器测试：traceId/requestId 解析、响应头回写、MDC 填充与清理、traceparent 提取。
 */
class RequestContextFilterTest {

    private final RequestContextFilter filter = new RequestContextFilter();

    @Test
    void shouldGenerateIdsAndEchoHeadersWhenNoHeaderPresent() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/demand/order/list");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        Pattern idPattern = Pattern.compile("[0-9a-f]{32}");
        assertTrue(idPattern.matcher(response.getHeader(RequestContextFilter.TRACE_ID_HEADER)).matches());
        assertTrue(idPattern.matcher(response.getHeader(RequestContextFilter.REQUEST_ID_HEADER)).matches());
    }

    @Test
    void shouldHonorIncomingTraceAndRequestHeaders() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/identity/getInfo");
        request.addHeader(RequestContextFilter.TRACE_ID_HEADER, "trace-abc");
        request.addHeader(RequestContextFilter.REQUEST_ID_HEADER, "req-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals("trace-abc", response.getHeader(RequestContextFilter.TRACE_ID_HEADER));
        assertEquals("req-123", response.getHeader(RequestContextFilter.REQUEST_ID_HEADER));
    }

    @Test
    void shouldExtractTraceIdFromW3cTraceparent() throws ServletException, IOException {
        String traceId32 = "4bf92f3577b34da6a3ce929d0e0e4736";
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/planning/batch/list");
        request.addHeader("traceparent", "00-" + traceId32 + "-00f067aa0ba902b7-01");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(traceId32, response.getHeader(RequestContextFilter.TRACE_ID_HEADER));
    }

    @Test
    void shouldSynthesizeTraceparentWhenAbsentSoSpanSharesTraceId() throws ServletException, IOException {
        String traceId32 = "4bf92f3577b34da6a3ce929d0e0e4736";
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/identity/getInfo");
        request.addHeader(RequestContextFilter.TRACE_ID_HEADER, traceId32);
        MockHttpServletResponse response = new MockHttpServletResponse();
        final String[] captured = new String[2];
        FilterChain capturingChain = (req, res) -> {
            captured[0] = ((jakarta.servlet.http.HttpServletRequest) req).getHeader("traceparent");
        };

        filter.doFilter(request, response, capturingChain);

        assertTrue(captured[0] != null && captured[0].startsWith("00-" + traceId32 + "-")
                && captured[0].endsWith("-01"), "downstream should receive a synthesized traceparent");
    }

    @Test
    void shouldNotOverrideExistingTraceparentHeader() throws ServletException, IOException {
        String existing = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-00";
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/identity/getInfo");
        request.addHeader(RequestContextFilter.TRACE_ID_HEADER, "aaaa");
        request.addHeader("traceparent", existing);
        MockHttpServletResponse response = new MockHttpServletResponse();
        final String[] captured = new String[1];
        FilterChain capturingChain = (req, res) ->
                captured[0] = ((jakarta.servlet.http.HttpServletRequest) req).getHeader("traceparent");

        filter.doFilter(request, response, capturingChain);

        assertEquals(existing, captured[0], "existing traceparent must not be overridden");
    }

    @Test
    void shouldPopulateMdcDuringChainAndClearAfterwards() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/execute/event");
        request.setRemoteAddr("10.0.0.9");
        MockHttpServletResponse response = new MockHttpServletResponse();

        final String[] captured = new String[3];
        FilterChain capturingChain = (req, res) -> {
            captured[0] = MDC.get("traceId");
            captured[1] = MDC.get("httpMethod");
            captured[2] = MDC.get("clientIp");
        };

        filter.doFilter(request, response, capturingChain);

        assertEquals(response.getHeader(RequestContextFilter.TRACE_ID_HEADER), captured[0]);
        assertEquals("POST", captured[1]);
        assertEquals("10.0.0.9", captured[2]);
        assertNull(MDC.get("traceId"));
        assertNull(MDC.get("requestId"));
    }
}
