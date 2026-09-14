package com.product.planning;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * product-planning 骨架冒烟测试（离线，禁用 Nacos 注册/配置）。
 */
@SpringBootTest(properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.service-registry.auto-registration.enabled=false",
        // 离线单测关闭本地验签安全链（无 JWKS 可拉取）；live 环境默认开启（ADR-0003）。
        // cloud-security 引入 spring-security-web/config 后，还需排除 Boot 默认 servlet 安全链，
        // 否则 @ConditionalOnDefaultWebSecurity 的兜底链会对 /skeleton/info 返回 401。
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration,org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration,org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration",
        "product.security.enabled=false"
})
@AutoConfigureMockMvc
class PlanningApplicationTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void skeletonInfoShouldExposeServiceIdentity() throws Exception {
        mockMvc.perform(get("/skeleton/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("product-planning"))
                .andExpect(jsonPath("$.phase").value(1));
    }

    @Test
    void everyResponseShouldCarryTraceHeaderFromRequestContextFilter() throws Exception {
        mockMvc.perform(get("/skeleton/info").header("X-Trace-Id", "test-trace-id"))
                .andExpect(header().string("X-Trace-Id", "test-trace-id"))
                .andExpect(header().exists("X-Request-Id"));
    }

    @Test
    void healthEndpointShouldBeUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
