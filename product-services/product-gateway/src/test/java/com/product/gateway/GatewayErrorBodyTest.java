package com.product.gateway;

import com.product.gateway.web.GatewayErrorBodies;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 网关统一错误体契约：与 product-cloud-common AjaxResult 信封形状一致，
 * 401 消息与单体 AuthenticationEntryPointImpl 逐字节一致。
 */
class GatewayErrorBodyTest {

    @Test
    void unauthorizedBodyShouldMatchMonolithContract() {
        assertEquals("{\"msg\":\"请求访问：/system/menu/list，认证失败，无法访问系统资源\",\"code\":401}",
                GatewayErrorBodies.unauthorized("/system/menu/list"));
    }

    @Test
    void rateLimitedBodyShouldUseUnifiedEnvelope() {
        assertEquals("{\"msg\":\"请求过于频繁，请稍后再试\",\"code\":429}",
                GatewayErrorBodies.tooManyRequests());
    }

    @Test
    void serviceUnavailableBodyShouldUseUnifiedEnvelope() {
        assertEquals("{\"msg\":\"服务暂时不可用，请稍后重试\",\"code\":503}",
                GatewayErrorBodies.serviceUnavailable());
        assertEquals("{\"msg\":\"请求路径不存在\",\"code\":404}", GatewayErrorBodies.notFound());
    }

    @Test
    void envelopeMapShouldKeepMsgFirstOrder() {
        var body = GatewayErrorBodies.envelopeMap("请求过于频繁，请稍后再试", 429);
        assertEquals("msg", body.keySet().iterator().next());
        assertEquals(429, body.get("code"));
    }
}
