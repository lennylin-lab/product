package com.product.identity;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import com.product.identity.auth.service.SysLoginService;
import com.product.identity.common.core.redis.RedisCache;
import com.product.identity.system.service.ISysDictDataService;
import com.product.identity.system.service.ISysDictTypeService;
import com.product.identity.system.service.ISysMenuService;
import com.product.identity.system.service.ISysRoleService;
import com.product.identity.system.service.ISysUserService;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * product-identity 冒烟测试（离线，禁用 Nacos 注册/配置、数据源与本地验签安全链——
 * 数据访问与 JWKS 拉取不参与离线单测，见 spec/backend/microservices-platform.md 测试约定）。
 *
 * <p>验证：上下文可启动（含安全/MyBatis-Plus/Redis 之外的基线 Bean）；
 * RequestContextFilter 回写 X-Trace-Id；健康检查可用。</p>
 */
@SpringBootTest(properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.service-registry.auto-registration.enabled=false",
        // 离线：无 MySQL/Redis/JWKS
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration,org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration,org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration",
        "product.security.enabled=false"
})
@AutoConfigureMockMvc
class IdentityApplicationTest {

    @Autowired
    MockMvc mockMvc;

    /** 业务服务依赖数据访问，测试上下文用 mock 替换（离线不触库）。 */
    @MockitoBean SysLoginService sysLoginService;
    @MockitoBean RedisCache redisCache;
    @MockitoBean org.springframework.data.redis.connection.RedisConnectionFactory redisConnectionFactory;
    @MockitoBean com.product.identity.system.mapper.SysUserMapper sysUserMapper;
    @MockitoBean com.product.identity.system.mapper.SysRoleMapper sysRoleMapper;
    @MockitoBean com.product.identity.system.mapper.SysMenuMapper sysMenuMapper;
    @MockitoBean com.product.identity.system.mapper.SysRoleMenuMapper sysRoleMenuMapper;
    @MockitoBean com.product.identity.system.mapper.SysUserRoleMapper sysUserRoleMapper;
    @MockitoBean com.product.identity.system.mapper.SysDictTypeMapper sysDictTypeMapper;
    @MockitoBean com.product.identity.system.mapper.SysDictDataMapper sysDictDataMapper;

    @MockitoBean ISysUserService userService;
    @MockitoBean ISysRoleService roleService;
    @MockitoBean ISysMenuService menuService;
    @MockitoBean ISysDictTypeService dictTypeService;
    @MockitoBean ISysDictDataService dictDataService;

    @Test
    void healthEndpointShouldBeUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void everyResponseShouldCarryTraceHeaderFromRequestContextFilter() throws Exception {
        mockMvc.perform(get("/actuator/health").header("X-Trace-Id", "test-trace-id"))
                .andExpect(header().string("X-Trace-Id", "test-trace-id"))
                .andExpect(header().exists("X-Request-Id"));
    }

    @Test
    void jwksEndpointShouldPublishCurrentKey() throws Exception {
        mockMvc.perform(get("/jwks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys").isArray())
                .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
                .andExpect(jsonPath("$.keys[0].alg").value("RS256"))
                .andExpect(jsonPath("$.keys[0].kid").isNotEmpty());
    }
}
