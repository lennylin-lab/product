package com.product.execution;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 直连端口防伪契约测试（Phase 2，ADR-0003：服务端本地验签安全链开启）。
 *
 * <p>无 token 直连受保护端点必须得到与单体逐字节一致的 401 错误体
 * （HTTP 200 + {"msg":"请求访问：<uri>，认证失败，无法访问系统资源","code":401}），
 * 证明 servlet 安全链已在本服务生效（Mapper 前 401，先于任何业务处理）。</p>
 *
 * <p>离线可跑：无 token 时不触发 JWKS 拉取；本地验签链路本身由
 * product-cloud-security 的 JwtAuthenticationChainTest 覆盖。</p>
 */
@SpringBootTest(properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.service-registry.auto-registration.enabled=false"
        // product.security.enabled 默认开启，此处不关闭
})
@AutoConfigureMockMvc
class ExecutionDirectPortSecurityTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void protectedEndpointWithoutTokenShouldReturnMonolithByteCompatible401() throws Exception {
        var result = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/skeleton/info")).andReturn();
        assertEquals(200, result.getResponse().getStatus());
        assertEquals("{\"msg\":\"请求访问：/skeleton/info，认证失败，无法访问系统资源\",\"code\":401}",
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
