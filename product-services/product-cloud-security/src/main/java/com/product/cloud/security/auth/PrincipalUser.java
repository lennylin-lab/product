package com.product.cloud.security.auth;

import com.product.cloud.security.jwt.TokenClaims;
import io.jsonwebtoken.Claims;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;

/**
 * 验签后重建的登录主体（服务端本地验签的唯一信任来源，ADR-0003 决策 4/7）。
 *
 * <p>语义对齐单体 {@code com.product.core.domain.LoginUser}：权限集合来自 token 内嵌
 * {@code permissions} claims（新鲜度由 TTL 约束，与现状一致）；token 不携带角色，
 * 因此 {@code hasRole} 类校验行为与单体 token 重建后的语义相同（恒为无角色）。</p>
 */
public class PrincipalUser implements UserDetails, UserPrincipalView {

    private static final long serialVersionUID = 1L;

    private final Long userId;
    private final String username;
    private final String avatar;
    private final Long loginTime;
    private final Long expireTime;
    private final String ipaddr;
    private final String loginLocation;
    private final String browser;
    private final String os;
    private final Set<String> permissions;
    /** jti：预留强制失效（黑名单）能力，当前登出保持现状（客户端删 token，无服务端状态）。 */
    private final String tokenId;

    public PrincipalUser(Claims claims, Set<String> permissions) {
        Object userIdRaw = claims.get(TokenClaims.USER_ID);
        this.userId = userIdRaw != null ? Long.valueOf(userIdRaw.toString()) : null;
        this.username = claims.getSubject();
        this.avatar = claims.get(TokenClaims.AVATAR, String.class);
        this.loginTime = toLong(claims.get(TokenClaims.LOGIN_TIME));
        this.expireTime = toLong(claims.get(TokenClaims.EXPIRE_TIME));
        this.ipaddr = claims.get(TokenClaims.IPADDR, String.class);
        this.loginLocation = claims.get(TokenClaims.LOGIN_LOCATION, String.class);
        this.browser = claims.get(TokenClaims.BROWSER, String.class);
        this.os = claims.get(TokenClaims.OS, String.class);
        this.permissions = permissions;
        this.tokenId = claims.getId();
    }

    public Long getUserId() {
        return userId;
    }

    @Override
    public String getUsername() {
        return username;
    }

    public String getAvatar() {
        return avatar;
    }

    public Long getLoginTime() {
        return loginTime;
    }

    public Long getExpireTime() {
        return expireTime;
    }

    public String getIpaddr() {
        return ipaddr;
    }

    public String getLoginLocation() {
        return loginLocation;
    }

    public String getBrowser() {
        return browser;
    }

    public String getOs() {
        return os;
    }

    public Set<String> getPermissions() {
        return permissions;
    }

    public String getTokenId() {
        return tokenId;
    }

    /** token 无服务端状态，密码不出现在认证主体中。 */
    @Override
    public String getPassword() {
        return null;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    /**
     * 方法级鉴权走 {@code @ss}（PermissionService），与单体一致不由 authorities 承担；
     * 空集合避免单体 LoginUser.getAuthorities() 返回 null 的空指针等价问题。
     */
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return Collections.emptyList();
    }

    private static Long toLong(Object value) {
        return value != null ? Long.valueOf(value.toString()) : null;
    }
}
