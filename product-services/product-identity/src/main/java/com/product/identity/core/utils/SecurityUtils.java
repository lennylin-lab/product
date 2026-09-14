package com.product.identity.core.utils;

import com.product.cloud.security.auth.PrincipalUser;
import com.product.cloud.common.exception.ServiceException;
import com.product.identity.common.constant.HttpStatus;
import com.product.identity.domain.entity.SysRole;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.util.PatternMatchUtils;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 安全工具（identity 域内使用）。
 *
 * <p>与单体 {@code com.product.core.utils.SecurityUtils} 方法签名保持一致，内部委托：
 * 请求态身份来自 {@code JwtAuthenticationFilter} 本地验签写入的 {@link PrincipalUser}
 * （服务只信任验签结果，ADR-0003）；登录处理过程中的临时主体为 {@code LoginPrincipal}。</p>
 */
@Slf4j
public class SecurityUtils {

    public static final String ALL_PERMISSION = "*:*:*";
    public static final String SUPER_ADMIN = "admin";

    public static Long getUserId() {
        try {
            return getLoginUser().getUserId();
        } catch (Exception e) {
            throw new ServiceException("获取用户ID异常", HttpStatus.UNAUTHORIZED);
        }
    }

    public static String getUsername() {
        try {
            return getLoginUser().getUsername();
        } catch (Exception e) {
            throw new ServiceException("获取用户账户异常", HttpStatus.UNAUTHORIZED);
        }
    }

    /** 当前用户（兼容登录主体 LoginPrincipal 与请求态验签主体 PrincipalUser 两种来源）。 */
    public static com.product.cloud.security.auth.UserPrincipalView getLoginUser() {
        try {
            Object principal = getAuthentication().getPrincipal();
            if (principal instanceof com.product.cloud.security.auth.UserPrincipalView view) {
                return view;
            }
            throw new ServiceException("获取用户信息异常", HttpStatus.UNAUTHORIZED);
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("获取用户信息异常", HttpStatus.UNAUTHORIZED);
        }
    }

    public static Authentication getAuthentication() {
        return org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
    }

    public static String encryptPassword(String password) {
        return new BCryptPasswordEncoder().encode(password);
    }

    public static boolean matchesPassword(String rawPassword, String encodedPassword) {
        return new BCryptPasswordEncoder().matches(rawPassword, encodedPassword);
    }

    public static boolean isAdmin(Long userId) {
        return userId != null && 1L == userId;
    }

    public static boolean hasPermi(String permission) {
        return hasPermi(getLoginUser().getPermissions(), permission);
    }

    public static boolean hasPermi(Collection<String> authorities, String permission) {
        if (authorities == null) {
            return false;
        }
        return authorities.stream().filter(text -> text != null && !text.isEmpty())
                .anyMatch(x -> ALL_PERMISSION.equals(x) || PatternMatchUtils.simpleMatch(x, permission));
    }

    public static boolean hasRole(String role) {
        List<SysRole> roleList = null;
        return hasRole(roleList == null ? List.of() : roleList.stream().map(SysRole::getRoleKey).collect(Collectors.toSet()), role);
    }

    public static boolean hasRole(Collection<String> roles, String role) {
        return roles.stream().filter(text -> text != null && !text.isEmpty())
                .anyMatch(x -> SUPER_ADMIN.equals(x) || PatternMatchUtils.simpleMatch(x, role));
    }
}
