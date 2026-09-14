package com.product.demand;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * product-demand 冒烟测试（离线，禁用 Nacos 注册/配置、数据源与本地验签安全链——
 * 数据访问与 JWKS 拉取不参与离线单测，见 spec/backend/microservices-platform.md 测试约定）。
 */
@SpringBootTest(properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.service-registry.auto-registration.enabled=false",
        // Phase 5：离线单测关闭事件基础设施（无 Rabbit/JDBC；服务 yml 默认 enabled=true）。
        // 同时排除 Rabbit 自动装配——amqp starter 会注册 RabbitHealthIndicator，
        // 离线无 Rabbit 时 /actuator/health 会 DOWN → 503。
        "product.messaging.enabled=false",
        // 离线：无 MySQL/JWKS。排除 DataSource/MyBatis-Plus/Rabbit 自动装配，并关闭本地验签安全链与 Boot 默认链。
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration,org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration,org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration,org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration,org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration",
        "product.security.enabled=false"
})
@AutoConfigureMockMvc
class DemandApplicationTest {

    @Autowired
    MockMvc mockMvc;

    /** 数据访问离线替换（MyBatis-Plus 自动装配已排除，Mapper 用 mock 占位）。 */
    @MockitoBean com.product.demand.mapper.CustomerMapper customerMapper;
    @MockitoBean com.product.demand.mapper.CustomerOrderMapper customerOrderMapper;
    @MockitoBean com.product.demand.mapper.OrderLineMapper orderLineMapper;
    /** Phase 4：需求域版本计数 Mapper（数据访问离线替换）。 */
    @MockitoBean com.product.demand.mapper.DemandDataVersionMapper demandDataVersionMapper;
    /** Phase 5：planning 批次状态投影 Mapper（数据访问离线替换；投影删除挂接订单行/订单删除链）。 */
    @MockitoBean com.product.demand.mapper.PlanningBatchStateMapper planningBatchStateMapper;

    @Test
    void skeletonInfoShouldExposeServiceIdentity() throws Exception {
        mockMvc.perform(get("/skeleton/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("product-demand"))
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
