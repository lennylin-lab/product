package com.product.identity.auth.context;

import org.springframework.security.core.Authentication;

/**
 * 认证上下文（单体同名类的 identity 移植；密码校验期间向 SysPasswordService 传递凭证）。
 */
public class AuthenticationContextHolder {

    private static final ThreadLocal<Authentication> CONTEXT = new ThreadLocal<>();

    public static Authentication getContext() {
        return CONTEXT.get();
    }

    public static void setContext(Authentication authentication) {
        CONTEXT.set(authentication);
    }

    public static void clearContext() {
        CONTEXT.remove();
    }
}
