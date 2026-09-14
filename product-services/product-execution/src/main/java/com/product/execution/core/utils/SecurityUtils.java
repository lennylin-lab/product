package com.product.execution.core.utils;

import com.product.cloud.security.auth.PrincipalUser;
import com.product.execution.common.constant.HttpStatus;
import com.product.execution.common.exception.ServiceException;
import org.springframework.security.core.Authentication;
import org.springframework.util.PatternMatchUtils;

import java.util.Collection;

/**
 * 安全工具（demand 域内使用；identity SecurityUtils 的业务域裁剪版：本域无角色语义，
 * 仅保留单体 SecurityUtils 在业务模块中实际被读取的方法）。
 *
 * <p>与单体 com.product.core.utils.SecurityUtils 方法签名保持一致，内部委托：
 * 请求态身份来自 JwtAuthenticationFilter 本地验签写入的 PrincipalUser
 * （服务只信任验签结果，ADR-0003）。</p>
 */
public class SecurityUtils {

    public static final String ALL_PERMISSION = "*:*:*";

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

    /** 当前用户（请求态本地验签主体 PrincipalUser）。 */
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
}
