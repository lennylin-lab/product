package com.product.identity.auth;

import com.product.identity.common.core.redis.RedisCache;
import com.product.identity.domain.entity.SysUser;
import com.product.identity.system.service.ISysUserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 服务身份令牌端点契约测试（Phase 4，离线）：
 * 1) 错误凭据 → 与单体登录同源错误体（HTTP 200 + code 500 + i18n "用户不存在/密码错误"）；
 * 2) 正确凭据（identity_db 种子 planning_svc）→ RS256 token，claims sub=planning_svc、
 *    permissions 为空集（业务域端点仅要求登录，无权限串）。
 *
 * <p>离线桩：ISysUserService 返回与 identity_schema.sql 种子一致的 planning_svc 行
 * （相同 BCrypt 哈希），RedisCache 返回无重试计数 —— 认证链
 * （AuthenticationManager/BCrypt/SysPasswordService）为真实组件。</p>
 */
@SpringBootTest(properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.service-registry.auto-registration.enabled=false",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration,org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration,org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration",
        "product.security.enabled=false"
})
@AutoConfigureMockMvc
class InternalServiceTokenControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean RedisCache redisCache;
    @MockitoBean org.springframework.data.redis.connection.RedisConnectionFactory redisConnectionFactory;
    @MockitoBean ISysUserService userService;
    // 数据访问 Mapper 占位（SysPermissionService 构造依赖 SysUserRoleMapper）
    @MockitoBean com.product.identity.system.mapper.SysUserMapper sysUserMapper;
    @MockitoBean com.product.identity.system.mapper.SysRoleMapper sysRoleMapper;
    @MockitoBean com.product.identity.system.mapper.SysMenuMapper sysMenuMapper;
    @MockitoBean com.product.identity.system.mapper.SysRoleMenuMapper sysRoleMenuMapper;
    @MockitoBean com.product.identity.system.mapper.SysUserRoleMapper sysUserRoleMapper;
    @MockitoBean com.product.identity.system.mapper.SysDictTypeMapper sysDictTypeMapper;
    @MockitoBean com.product.identity.system.mapper.SysDictDataMapper sysDictDataMapper;
    // 其余业务服务占位（不参与本链路）
    @MockitoBean com.product.identity.system.service.ISysRoleService roleService;
    @MockitoBean com.product.identity.system.service.ISysMenuService menuService;
    @MockitoBean com.product.identity.system.service.ISysDictTypeService dictTypeService;
    @MockitoBean com.product.identity.system.service.ISysDictDataService dictDataService;

    /** 与 identity_schema.sql 种子一致的 planning_svc 行（BCrypt(“planning-svc-dev-pwd”)）。 */
    private static final String SEED_HASH =
            "$2a$10$735KiuLjegsM30OBDnJfkOWYDV/DyfxlCP1.P0rbAASG8ViL17nAS";

    private SysUser seedUser() {
        SysUser user = new SysUser();
        user.setUserId(2L);
        user.setUserName("planning_svc");
        user.setPassword(SEED_HASH);
        user.setStatus("0");
        user.setDelFlag("0");
        return user;
    }

    private void stubSeedUser() {
        when(userService.selectUserByUserName("planning_svc")).thenReturn(seedUser());
        when(redisCache.getCacheObject(anyString())).thenReturn(null);
    }

    @Test
    void wrongCredentialsShouldReturnMonolithIdenticalErrorBody() throws Exception {
        stubSeedUser();
        mockMvc.perform(post("/internal/identity/service-token")
                        .contentType("application/json")
                        .content("{\"username\":\"planning_svc\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.msg").value("用户不存在/密码错误"));
    }

    @Test
    void unknownUserShouldReturnMonolithIdenticalErrorBody() throws Exception {
        when(userService.selectUserByUserName("nobody")).thenReturn(null);
        when(redisCache.getCacheObject(anyString())).thenReturn(null);
        mockMvc.perform(post("/internal/identity/service-token")
                        .contentType("application/json")
                        .content("{\"username\":\"nobody\",\"password\":\"whatever\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500));
    }

    @Test
    void validCredentialsShouldIssueRs256TokenWithEmptyPermissions() throws Exception {
        stubSeedUser();
        String body = mockMvc.perform(post("/internal/identity/service-token")
                        .contentType("application/json")
                        .content("{\"username\":\"planning_svc\",\"password\":\"planning-svc-dev-pwd\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String token = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(body).path("token").asText();
        org.junit.jupiter.api.Assertions.assertFalse(token.isEmpty(), "token must be issued");

        String[] parts = token.split("\\.");
        org.junit.jupiter.api.Assertions.assertEquals(3, parts.length, "JWT must have 3 segments");
        String header = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        org.junit.jupiter.api.Assertions.assertTrue(header.contains("RS256"), "alg must be RS256: " + header);
        org.junit.jupiter.api.Assertions.assertTrue(header.contains("kid"), "header must carry kid: " + header);
        org.junit.jupiter.api.Assertions.assertTrue(payload.contains("\"sub\":\"planning_svc\""),
                "sub must be planning_svc: " + payload);
        org.junit.jupiter.api.Assertions.assertTrue(payload.contains("\"permissions\":[]"),
                "permissions must be empty set: " + payload);
        verify(userService).selectUserByUserName("planning_svc");
    }
}
