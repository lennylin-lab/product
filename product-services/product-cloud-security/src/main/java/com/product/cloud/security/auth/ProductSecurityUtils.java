package com.product.cloud.security.auth;

import com.product.cloud.common.api.ApiStatus;
import com.product.cloud.common.exception.ServiceException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Set;

/**
 * 安全工具（服务端，语义对齐单体 {@code com.product.core.utils.SecurityUtils}）。
 *
 * <p>唯一信任来源：{@link JwtAuthenticationFilter} 本地验签后写入 SecurityContext 的
 * {@link PrincipalUser}。直连服务端口的请求若未携带有效签名 token，这里会因
 * SecurityContext 为空抛出与单体一致的 401 ServiceException。</p>
 */
public final class ProductSecurityUtils {

    /** 管理员通配权限（单体 Constants.ALL_PERMISSION）。 */
    public static final String ALL_PERMISSION = "*:*:*";

    private ProductSecurityUtils() {
    }

    public static Long getUserId() {
        try {
            return getLoginUser().getUserId();
        } catch (Exception e) {
            throw new ServiceException("获取用户ID异常", ApiStatus.UNAUTHORIZED);
        }
    }

    public static String getUsername() {
        try {
            return getLoginUser().getUsername();
        } catch (Exception e) {
            throw new ServiceException("获取用户账户异常", ApiStatus.UNAUTHORIZED);
        }
    }

    public static PrincipalUser getLoginUser() {
        try {
            return (PrincipalUser) getAuthentication().getPrincipal();
        } catch (Exception e) {
            throw new ServiceException("获取用户信息异常", ApiStatus.UNAUTHORIZED);
        }
    }

    public static Authentication getAuthentication() {
        return SecurityContextHolder.getContext().getAuthentication();
    }

    /** 判断用户 ID 是否为超级管理员（单体规则：userId == 1）。 */
    public static boolean isAdmin(Long userId) {
        return userId != null && 1L == userId;
    }

    public static boolean hasPermi(String permission) {
        return hasPermi(getLoginUser().getPermissions(), permission);
    }

    /** 权限匹配语义与单体 SecurityUtils.hasPermi 一致：通配 *:*:* 放行 + 简单通配符。 */
    public static boolean hasPermi(Set<String> authorities, String permission) {
        if (authorities == null) {
            return false;
        }
        return authorities.stream().filter(text -> text != null && !text.isEmpty())
                .anyMatch(x -> ALL_PERMISSION.equals(x)
                        || org.springframework.util.PatternMatchUtils.simpleMatch(x, permission));
    }
}
