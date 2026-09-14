package com.product.cloud.security.jwt;

/**
 * JWT claims 键名常量（ADR-0003 决策 5：claims 兼容迁移）。
 *
 * <p>自定义 claims 与单体 {@code product-core} JwtUtils 保持同名同义，
 * 另补充标准 {@code iss/iat/exp/jti} 与 header {@code kid}。
 * 权限串格式保持 {@code {module}:{resource}:{action}}，管理员通配 {@code *:*:*}。</p>
 */
public final class TokenClaims {

    /** 标准签发者（iss），固定为 identity 的 Nacos 服务名 */
    public static final String ISSUER = "product-identity";

    /** 用户名（= 标准 sub，与单体 Constants.JWT_USERNAME 一致） */
    public static final String USERNAME = "sub";

    /** 用户 ID */
    public static final String USER_ID = "userId";

    /** 权限串列表（单体登录时写入什么就是什么：管理员 *:*:*，普通用户为空集合） */
    public static final String PERMISSIONS = "permissions";

    /** 登录时间（epoch millis） */
    public static final String LOGIN_TIME = "loginTime";

    /** 过期时间（epoch millis，与标准 exp 同值） */
    public static final String EXPIRE_TIME = "expireTime";

    /** 登录 IP */
    public static final String IPADDR = "ipaddr";

    /** 登录地点 */
    public static final String LOGIN_LOCATION = "loginLocation";

    /** 浏览器 */
    public static final String BROWSER = "browser";

    /** 操作系统 */
    public static final String OS = "os";

    /** 用户显示名 */
    public static final String USER_NAME = "userName";

    /** 头像 */
    public static final String AVATAR = "avatar";

    private TokenClaims() {
    }
}
