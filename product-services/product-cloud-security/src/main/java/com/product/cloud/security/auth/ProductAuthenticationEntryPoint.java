package com.product.cloud.security.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.product.cloud.common.api.AjaxResult;
import com.product.cloud.common.api.ApiStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;

/**
 * 认证失败处理器：与单体 {@code product-auth} AuthenticationEntryPointImpl 输出逐字节兼容。
 *
 * <p>单体用 fastjson2 序列化 {@code AjaxResult}（HashMap，迭代序 code→msg）且
 * {@code ServletUtils.renderString} 固定写 HTTP 200，因此实际响应为
 * {@code HTTP 200 + {"code":401,"msg":"请求访问：<uri>，认证失败，无法访问系统资源"}}。
 * Jackson 对同一 HashMap 的序列化结果与 fastjson2 完全一致（键序相同、无空格）。</p>
 */
public class ProductAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException e)
            throws IOException {
        int code = ApiStatus.UNAUTHORIZED;
        String msg = String.format("请求访问：%s，认证失败，无法访问系统资源", request.getRequestURI());
        renderString(response, MAPPER.writeValueAsString(AjaxResult.error(code, msg)));
    }

    /**
     * 与单体 ServletUtils.renderString 相同：HTTP 200 + application/json + utf-8。
     */
    static void renderString(HttpServletResponse response, String string) throws IOException {
        response.setStatus(200);
        response.setContentType("application/json");
        response.setCharacterEncoding("utf-8");
        response.getWriter().print(string);
    }
}
