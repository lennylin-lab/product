package com.product.masterdata;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.product.masterdata.mapper.CalendarMapper;
import com.product.masterdata.mapper.MachineMapper;
import com.product.masterdata.mapper.MachineMoldCompatibilityMapper;
import com.product.masterdata.mapper.MasterDataVersionMapper;
import com.product.masterdata.mapper.MoldMapper;
import com.product.masterdata.mapper.ProductMapper;
import com.product.masterdata.mapper.ProductMoldParamMapper;
import com.product.masterdata.mapper.ProductRouteMapper;
import com.product.masterdata.mapper.ResourceCapabilityMapper;
import com.product.masterdata.mapper.ResourceMapper;
import com.product.masterdata.mapper.RouteOperationMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * product-master-data 冒烟测试（离线，禁用 Nacos 注册/配置、数据源与本地验签安全链——
 * 数据访问与 JWKS 拉取不参与离线单测，见 spec/backend/microservices-platform.md 测试约定）。
 */
@SpringBootTest(properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.service-registry.auto-registration.enabled=false",
        // 离线：无 MySQL/JWKS。排除 DataSource/MyBatis-Plus 自动装配（否则 /actuator/health
        // 的 DB health indicator 因连不上库返回 DOWN → 503），并关闭本地验签安全链与 Boot 默认链。
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration,org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration,org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration,org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration",
        "product.security.enabled=false"
})
@AutoConfigureMockMvc
class MasterDataApplicationTest {

    @Autowired
    MockMvc mockMvc;

    /** 数据访问离线替换（MyBatis-Plus 自动装配已排除，Mapper 用 mock 占位）。 */
    @MockitoBean CalendarMapper calendarMapper;
    @MockitoBean MachineMapper machineMapper;
    @MockitoBean MachineMoldCompatibilityMapper machineMoldCompatibilityMapper;
    @MockitoBean MasterDataVersionMapper masterDataVersionMapper;
    @MockitoBean MoldMapper moldMapper;
    @MockitoBean ProductMapper productMapper;
    @MockitoBean ProductMoldParamMapper productMoldParamMapper;
    @MockitoBean ProductRouteMapper productRouteMapper;
    @MockitoBean ResourceCapabilityMapper resourceCapabilityMapper;
    @MockitoBean ResourceMapper resourceMapper;
    @MockitoBean RouteOperationMapper routeOperationMapper;

    @Test
    void skeletonInfoShouldExposeServiceIdentity() throws Exception {
        mockMvc.perform(get("/skeleton/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("product-master-data"))
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
