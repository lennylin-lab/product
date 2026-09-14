package com.product.identity.auth.service;

import com.product.identity.auth.domain.LoginPrincipal;
import com.product.identity.common.constant.CacheConstants;
import com.product.identity.common.constant.UserConstants;
import com.product.identity.common.core.redis.RedisCache;
import com.product.cloud.common.exception.ServiceException;
import com.product.identity.common.exception.user.CaptchaException;
import com.product.identity.common.exception.user.CaptchaExpireException;
import com.product.identity.common.exception.user.UserNotExistsException;
import com.product.identity.common.exception.user.UserPasswordNotMatchException;
import com.product.identity.common.utils.DateUtils;
import com.product.identity.common.utils.StringUtils;
import com.product.identity.common.utils.ip.IpUtils;
import com.product.identity.domain.entity.SysUser;
import com.product.identity.system.service.ISysUserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * 登录校验方法（单体 SysLoginService 的 identity 移植，流程与错误消息逐一对应）。
 *
 * <p>差异说明：token 签发由 {@link JwtTokenService}（RS256 + JWKS，ADR-0003）承担，
 * 其余（验证码校验、前置校验、AuthenticationManager 认证、登录信息记录）与单体一致。</p>
 */
@Slf4j
@Component
public class SysLoginService {

    @Autowired
    private JwtTokenService tokenService;

    @Autowired
    private AuthenticationManager authenticationManager;

    @Autowired
    private RedisCache redisCache;

    @Autowired
    private ISysUserService userService;

    /**
     * 登录验证
     */
    public String login(String username, String password, String code, String uuid) {
        // 验证码校验
        validateCaptcha(username, code, uuid);
        // 登录前置校验
        loginPreCheck(username, password);
        // 用户验证
        Authentication authentication;
        try {
            // 创建认证令牌
            UsernamePasswordAuthenticationToken authenticationToken =
                    new UsernamePasswordAuthenticationToken(username, password);
            // 设置到 threadLocal 中
            com.product.identity.auth.context.AuthenticationContextHolder.setContext(authenticationToken);
            // 该方法会去调用 UserDetailsServiceImpl.loadUserByUsername
            authentication = authenticationManager.authenticate(authenticationToken);
        } catch (Exception e) {
            if (e instanceof BadCredentialsException) {
                throw new UserPasswordNotMatchException();
            } else {
                throw new ServiceException(e.getMessage());
            }
        } finally {
            // 清理 threadLocal
            com.product.identity.auth.context.AuthenticationContextHolder.clearContext();
        }
        LoginPrincipal loginPrincipal = (LoginPrincipal) authentication.getPrincipal();
        recordLoginInfo(loginPrincipal.getUserId());
        // 生成 token（RS256 JWT）
        return tokenService.createToken(loginPrincipal.getUser(), loginPrincipal.getPermissions());
    }

    /**
     * 校验验证码
     */
    public void validateCaptcha(String username, String code, String uuid) {
        String verifyKey = CacheConstants.CAPTCHA_CODE_KEY + StringUtils.nvl(uuid, "");
        log.info("当前缓存中验证码键: {}", redisCache.keys(CacheConstants.CAPTCHA_CODE_KEY));
        String captcha = redisCache.getCacheObject(verifyKey);
        log.info("当前验证码: {}", captcha);
        if (captcha == null) {
            throw new CaptchaExpireException();
        }
        redisCache.deleteObject(verifyKey);
        if (!code.equalsIgnoreCase(captcha)) {
            throw new CaptchaException();
        }
    }

    /**
     * 登录前置校验
     */
    public void loginPreCheck(String username, String password) {
        // 用户名或密码为空 错误
        if (StringUtils.isEmpty(username) || StringUtils.isEmpty(password)) {
            throw new UserNotExistsException();
        }
        // 密码如果不在指定范围内 错误
        if (password.length() < UserConstants.PASSWORD_MIN_LENGTH
                || password.length() > UserConstants.PASSWORD_MAX_LENGTH) {
            throw new UserPasswordNotMatchException();
        }
        // 用户名不在指定范围内 错误
        if (username.length() < UserConstants.USERNAME_MIN_LENGTH
                || username.length() > UserConstants.USERNAME_MAX_LENGTH) {
            throw new UserPasswordNotMatchException();
        }
    }

    /**
     * 记录登录信息
     */
    public void recordLoginInfo(Long userId) {
        SysUser sysUser = new SysUser();
        sysUser.setUserId(userId);
        // 获取客户端 ip
        sysUser.setLoginIp(IpUtils.getIpAddr());
        // 获取登录时间
        sysUser.setLoginDate(DateUtils.getNowDate());
        userService.updateUserProfile(sysUser);
    }
}
