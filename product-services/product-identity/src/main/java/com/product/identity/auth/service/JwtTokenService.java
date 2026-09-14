package com.product.identity.auth.service;

import com.product.cloud.security.jwt.JwtSigner;
import com.product.cloud.security.jwt.TokenClaims;
import com.product.identity.common.utils.ServletUtils;
import com.product.identity.common.utils.StringUtils;
import com.product.identity.common.utils.ip.AddressUtils;
import com.product.identity.common.utils.ip.IpUtils;
import com.product.identity.domain.entity.SysUser;
import eu.bitwalker.useragentutils.UserAgent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Token 签发服务（单体 JwtUtils.createToken 的 RS256 化，ADR-0003 决策 2/5）。
 *
 * <p>claims 与单体自包含 JWT 同名同义（sub/userId/permissions/loginTime/expireTime/
 * ipaddr/loginLocation/browser/os/userName/avatar），补充标准 iss/iat/exp/jti 与
 * header kid；签发私钥仅 Identity 持有，公钥经 GET /jwks 发布。</p>
 */
@Slf4j
@Component
public class JwtTokenService {

    private final JwtKeyManager keyManager;

    @Value("${product.identity.jwt.expire-minutes:300}")
    private long expireMinutes;

    public JwtTokenService(JwtKeyManager keyManager) {
        this.keyManager = keyManager;
    }

    /**
     * 创建 JWT 令牌（permissions 语义与单体登录一致：管理员 *:*:*，普通用户为登录时集合）。
     */
    public String createToken(SysUser user, Set<String> permissions) {
        Map<String, String> deviceClaims = new HashMap<>();
        // 登录设备信息（单体 setUserAgent 逻辑）
        String ip = IpUtils.getIpAddr();
        deviceClaims.put(TokenClaims.IPADDR, ip);
        deviceClaims.put(TokenClaims.LOGIN_LOCATION, AddressUtils.getRealAddressByIP(ip));
        UserAgent userAgent = UserAgent.parseUserAgentString(ServletUtils.getRequest().getHeader("User-Agent"));
        deviceClaims.put(TokenClaims.BROWSER, userAgent.getBrowser().getName());
        deviceClaims.put(TokenClaims.OS, userAgent.getOperatingSystem().getName());

        String username = user != null ? user.getUserName() : null;
        String avatar = user != null ? user.getAvatar() : null;
        Long userId = user != null ? user.getUserId() : null;

        return signer().sign(username, userId, permissions, deviceClaims, username, avatar, expireMinutes);
    }

    private JwtSigner signer() {
        return keyManager.signer();
    }

    /** 单体 JwtUtils.setLoginUser 为空实现（自包含 JWT 无缓存）；identity 保持同语义。 */
    public void setLoginUser(com.product.identity.auth.domain.LoginPrincipal loginPrincipal) {
        // 自包含 JWT：无服务端会话缓存，保持单体空实现语义
    }
}
