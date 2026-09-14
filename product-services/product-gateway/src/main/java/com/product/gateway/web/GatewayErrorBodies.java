package com.product.gateway.web;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 网关统一错误体（与 product-cloud-common 的 AjaxResult 信封形状一致）。
 *
 * <p>键序与单体/服务端错误体一致（msg → code，HashMap/Jackson 迭代序）；
 * 网关非代理错误（404/503/限流等）也保持 {code, msg} JSON 信封（Phase 1 遗留项）。</p>
 */
public final class GatewayErrorBodies {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GatewayErrorBodies() {
    }

    /** 认证失败（与单体 AuthenticationEntryPointImpl 消息逐字节一致）。 */
    public static String unauthorized(String requestUri) {
        return envelope("请求访问：" + requestUri + "，认证失败，无法访问系统资源", 401);
    }

    /** Sentinel 限流/熔断。 */
    public static String tooManyRequests() {
        return envelope("请求过于频繁，请稍后再试", 429);
    }

    /** 路由后无可用服务实例等下游不可用场景。 */
    public static String serviceUnavailable() {
        return envelope("服务暂时不可用，请稍后重试", 503);
    }

    /** 网关未匹配任何路由。 */
    public static String notFound() {
        return envelope("请求路径不存在", 404);
    }

    /** 兜底错误。 */
    public static String internalError() {
        return envelope("系统繁忙，请稍后重试", 500);
    }

    /**
     * 统一信封：msg 在前、code 在后（与服务端 Jackson 对 HashMap 的迭代序一致）。
     */
    public static Map<String, Object> envelopeMap(String msg, int code) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("msg", msg);
        body.put("code", code);
        return body;
    }

    /**
     * 统一信封序列化：走 Jackson（Phase 2 检查项修正——msg 内嵌请求 URI 等外部输入，
     * 手工拼接不转义会产生非法 JSON；Jackson 与服务端序列化器同为迭代序 + 紧凑格式，
     * 标准错误体与单体逐字节一致，含引号/反斜杠等字符时输出合法转义）。
     */
    public static String envelope(String msg, int code) {
        try {
            return MAPPER.writeValueAsString(envelopeMap(msg, code));
        } catch (Exception e) {
            // 序列化 LinkedHashMap 不可能失败；兜底保证响应仍为合法 JSON
            return "{\"code\":" + code + "}";
        }
    }
}
