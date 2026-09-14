package com.product.identity.auth;

import com.product.identity.auth.service.SysLoginService;
import com.product.identity.auth.service.SysPasswordService;
import com.product.identity.common.core.redis.RedisCache;
import com.product.identity.common.exception.user.UserPasswordNotMatchException;
import com.product.identity.common.exception.user.CaptchaException;
import com.product.identity.common.exception.user.CaptchaExpireException;
import com.product.identity.domain.entity.SysUser;
import com.product.identity.system.service.ISysUserService;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 登录服务单测（Mockito，离线）：验证码/前置校验/认证失败路径与单体语义一致。
 */
class SysLoginServiceTest {

    private final AuthenticationManager authenticationManager = mock(AuthenticationManager.class);
    private final RedisCache redisCache = mock(RedisCache.class);
    private final ISysUserService userService = mock(ISysUserService.class);
    private final com.product.identity.auth.service.JwtTokenService tokenService =
            mock(com.product.identity.auth.service.JwtTokenService.class);
    private final SysPasswordService passwordService = mock(SysPasswordService.class);

    private SysLoginService service() {
        SysLoginService service = new SysLoginService();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "tokenService", tokenService);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "authenticationManager", authenticationManager);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "redisCache", redisCache);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "userService", userService);
        return service;
    }

    @Test
    void expiredCaptchaShouldThrowCaptchaExpire() {
        when(redisCache.getCacheObject("identity:captcha_codes:uuid-1")).thenReturn(null);
        assertThrows(CaptchaExpireException.class, () -> service().login("admin", "admin123", "5", "uuid-1"));
        verify(authenticationManager, never()).authenticate(any());
    }

    @Test
    void wrongCaptchaShouldThrowCaptchaError() {
        when(redisCache.getCacheObject("identity:captcha_codes:uuid-1")).thenReturn("7");
        assertThrows(CaptchaException.class, () -> service().login("admin", "admin123", "5", "uuid-1"));
    }

    @Test
    void shortPasswordShouldFailPreCheckWithUserPasswordNotMatch() {
        // 单体 loginPreCheck：密码长度越界抛 UserPasswordNotMatchException（i18n user.password.not.match）
        when(redisCache.getCacheObject(any())).thenReturn("5");
        assertThrows(UserPasswordNotMatchException.class, () -> service().login("admin", "abc", "5", "u"));
        verify(authenticationManager, never()).authenticate(any());
    }

    @Test
    void badCredentialsShouldMapToPasswordNotMatch() {
        // 单体 SysLoginService：BadCredentialsException → UserPasswordNotMatchException（"用户不存在/密码错误"）
        when(redisCache.getCacheObject(any())).thenReturn("5");
        when(authenticationManager.authenticate(any()))
                .thenThrow(new BadCredentialsException("Bad credentials"));
        assertThrows(UserPasswordNotMatchException.class, () -> service().login("admin", "admin123", "5", "u"));
    }

    @Test
    void successfulLoginShouldRecordLoginInfoAndReturnToken() {
        // recordLoginInfo/token 签发读取当前请求（IP/User-Agent），单测中注入模拟请求
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(
                        new org.springframework.mock.web.MockHttpServletRequest()));
        when(redisCache.getCacheObject(any())).thenReturn("5");
        SysUser user = new SysUser();
        user.setUserId(1L);
        user.setUserName("admin");
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                new com.product.identity.auth.domain.LoginPrincipal(user, java.util.Set.of("*:*:*")), null);
        when(authenticationManager.authenticate(any())).thenReturn(authentication);
        when(tokenService.createToken(eq(user), any())).thenReturn("jwt-token");

        String token = service().login("admin", "admin123", "5", "u");
        assertEquals("jwt-token", token);
        verify(userService).updateUserProfile(any(SysUser.class));
    }
}
