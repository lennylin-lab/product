package com.product.demand.core.domain;

/**
 * 当前用户视图（identity 域内）。
 *
 * <p>统一两种主体来源：请求态 {@code PrincipalUser}（本地验签重建，ADR-0003）与
 * 登录处理期 {@code LoginPrincipal}（UserDetailsService 加载），接口只暴露
 * 单体 LoginUser 在 identity 内实际被读取的三个视图方法。</p>
 */
public interface CurrentUser extends com.product.cloud.security.auth.UserPrincipalView {
}
