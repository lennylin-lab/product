package com.product.cloud.security.auth;

import com.product.cloud.common.exception.ServiceException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.CollectionUtils;

import java.util.Set;

/**
 * 方法级鉴权 Bean（{@code @ss}），语义与单体 {@code product-auth} PermissionService 一致。
 *
 * <p>权限事实源为 token 内嵌 permissions（Identity 登录时写入，管理员 {@code *:*:*}），
 * 新鲜度由 TTL 约束——与单体现状一致（baselines.md §1.1 冻结）。角色类校验因 token
 * 不携带角色（单体自包含 JWT 重建 LoginUser 后 roles 亦为空）而恒为 false，行为保持。</p>
 *
 * <p>由 {@code ProductSecurityAutoConfiguration#permissionService} 注册，bean 名固定 {@code ss}。</p>
 */
public class PermissionService {

    public boolean hasPermi(String permission) {
        if (permission == null || permission.isEmpty()) {
            return false;
        }
        PrincipalUser loginUser = getLoginUser();
        if (loginUser == null || CollectionUtils.isEmpty(loginUser.getPermissions())) {
            return false;
        }
        return hasPermissions(loginUser.getPermissions(), permission);
    }

    public boolean lacksPermi(String permission) {
        return hasPermi(permission) != true;
    }

    public boolean hasAnyPermi(String permissions) {
        if (permissions == null || permissions.isEmpty()) {
            return false;
        }
        PrincipalUser loginUser = getLoginUser();
        if (loginUser == null || CollectionUtils.isEmpty(loginUser.getPermissions())) {
            return false;
        }
        Set<String> authorities = loginUser.getPermissions();
        for (String permission : permissions.split(",")) {
            if (permission != null && hasPermissions(authorities, permission)) {
                return true;
            }
        }
        return false;
    }

    public boolean hasRole(String role) {
        // 与单体一致：token 重建的 LoginUser.user.roles 为空 → CollectionUtils.isEmpty → hasRole 恒 false
        getLoginUser();
        return false;
    }

    public boolean lacksRole(String role) {
        return hasRole(role) != true;
    }

    public boolean hasAnyRoles(String roles) {
        return false;
    }

    private boolean hasPermissions(Set<String> permissions, String permission) {
        return permissions.contains("*:*:*") || permissions.contains(permission.trim());
    }

    private PrincipalUser getLoginUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof PrincipalUser principalUser) {
            return principalUser;
        }
        // 与单体 SecurityUtils.getLoginUser 相同：无认证主体时抛 401 业务异常（HTTP 200 + code 401）
        throw new ServiceException("获取用户信息异常", 401);
    }
}
