package com.product.cloud.security.auth;

import java.util.Set;

/**
 * 当前用户最小视图（跨模块安全地读取请求态身份的契约）。
 *
 * <p>请求态主体 {@link PrincipalUser} 实现本接口；各业务服务的登录期主体也实现其
 * 子接口，使安全工具与控制器对两种主体一视同仁（服务只信任本地验签结果）。</p>
 */
public interface UserPrincipalView {

    Long getUserId();

    String getUsername();

    Set<String> getPermissions();
}
