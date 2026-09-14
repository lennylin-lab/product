package com.product.identity.auth.domain;

import com.product.identity.core.domain.CurrentUser;
import com.product.identity.domain.entity.SysUser;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;

/**
 * 登录处理期的认证主体（单体 {@code LoginUser implements UserDetails} 的 identity 等价物）。
 *
 * <p>仅在登录认证链路（DaoAuthenticationProvider → SysLoginService）中存在，
 * 不进入序列化/token；请求态身份一律由 JWT 本地验签重建（ADR-0003 决策 4）。</p>
 */
public class LoginPrincipal implements UserDetails, CurrentUser {

    private static final long serialVersionUID = 1L;

    private final SysUser user;
    private final Set<String> permissions;

    public LoginPrincipal(SysUser user, Set<String> permissions) {
        this.user = user;
        this.permissions = permissions;
    }

    @Override
    public Long getUserId() {
        return user != null ? user.getUserId() : null;
    }

    @Override
    public String getUsername() {
        return user != null ? user.getUserName() : null;
    }

    @Override
    public Set<String> getPermissions() {
        return permissions;
    }

    public SysUser getUser() {
        return user;
    }

    @Override
    public String getPassword() {
        return user != null ? user.getPassword() : null;
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

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return Collections.emptyList();
    }
}
