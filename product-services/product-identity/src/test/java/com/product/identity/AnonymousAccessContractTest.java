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

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 匿名访问与 401 契约测试（本地验签安全链开启，ADR-0003）。
 *
 * <p>无 token 直连受保护端点（模拟"直连服务端口且未验签"）必须得到与单体逐字节一致的
 * 401 错误体：HTTP 200 + {"msg":"请求访问：<uri>，认证失败，无法访问系统资源","code":401}。</p>
 */
@SpringBootTest(properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.service-registry.auto-registration.enabled=false",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration"
})
@AutoConfigureMockMvc
class AnonymousAccessContractTest {

    @Autowired
    MockMvc mockMvc;

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
    void protectedEndpointWithoutTokenShouldReturnMonolithByteCompatible401() throws Exception {
        var result = mockMvc.perform(get("/system/menu/list")).andReturn();
        assertEquals(200, result.getResponse().getStatus());
        assertEquals("{\"msg\":\"请求访问：/system/menu/list，认证失败，无法访问系统资源\",\"code\":401}",
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void protectedEndpointWithForgedTokenShouldReturnSame401Body() throws Exception {
        var result = mockMvc.perform(get("/system/menu/list")
                        .header("Authorization", "Bearer fake.token.value")).andReturn();
        assertEquals(200, result.getResponse().getStatus());
        assertEquals("{\"msg\":\"请求访问：/system/menu/list，认证失败，无法访问系统资源\",\"code\":401}",
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void jwksShouldStayAnonymous() throws Exception {
        mockMvc.perform(get("/jwks")).andReturn().getResponse().getStatus();
        var result = mockMvc.perform(get("/jwks")).andReturn();
        assertEquals(200, result.getResponse().getStatus());
    }
}
