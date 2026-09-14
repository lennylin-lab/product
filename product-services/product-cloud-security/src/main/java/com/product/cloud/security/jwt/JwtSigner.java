package com.product.cloud.security.jwt;

import io.jsonwebtoken.Jwts;

import java.security.PrivateKey;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * JWT 签发器（仅 Identity 持有私钥，ADR-0003 决策 2）。
 *
 * <p>claims 与单体 {@code JwtUtils.createToken(LoginUser)} 保持同名同义
 * （sub=用户名、userId、permissions、loginTime、expireTime、ipaddr、loginLocation、
 * browser、os、userName、avatar），补充标准 {@code iss/iat/exp/jti} 与 header {@code kid}
 * （ADR-0003 决策 5）。exp 与 expireTime 同值——单体现状实际未强制过期的行为按 ADR 收敛。</p>
 *
 * <p>实现说明：标准 claims（iss/sub/jti/iat/exp）与自定义 claims 统一放入同一 map 后经
 * {@code claims(Map)} 一次性写入（jjwt 0.12 的 claims(Map) 为整体替换语义，避免混用两种
 * 设置方式导致标准 claims 被覆盖）。</p>
 */
public class JwtSigner {

    /** 令牌有效期默认 300 分钟（baselines.md §1.1：TTL 300 分钟）。 */
    public static final long DEFAULT_TTL_MINUTES = 300;

    private final PrivateKey privateKey;
    private final String kid;

    public JwtSigner(PrivateKey privateKey, String kid) {
        this.privateKey = privateKey;
        this.kid = kid;
    }

    /**
     * 签发 RS256 token。
     *
     * @param subject      用户名（单体 login 信息）
     * @param userId       用户 ID
     * @param permissions  权限集合（单体登录语义：管理员 {@code *:*:*}，普通用户为空集合）
     * @param deviceClaims 设备信息 claims（ipaddr/loginLocation/browser/os，可空）
     * @param userName     用户显示名（可空）
     * @param avatar       头像（可空）
     * @param ttlMinutes   有效期（分钟）
     */
    public String sign(String subject,
                       Long userId,
                       Iterable<String> permissions,
                       Map<String, String> deviceClaims,
                       String userName,
                       String avatar,
                       long ttlMinutes) {
        long now = System.currentTimeMillis();
        long expireAt = now + ttlMinutes * 60 * 1000L;

        Map<String, Object> claims = new HashMap<>();
        // 标准 claims（ADR-0003 决策 5）
        claims.put("iss", TokenClaims.ISSUER);
        claims.put("sub", subject);
        claims.put("jti", UUID.randomUUID().toString().replace("-", ""));
        claims.put("iat", new Date(now));
        claims.put("exp", new Date(expireAt));
        // 单体自定义 claims（同名同义迁移）
        claims.put(TokenClaims.USER_ID, userId);
        List<String> permissionList = new ArrayList<>();
        if (permissions != null) {
            permissions.forEach(permissionList::add);
        }
        claims.put(TokenClaims.PERMISSIONS, permissionList);
        claims.put(TokenClaims.LOGIN_TIME, now);
        claims.put(TokenClaims.EXPIRE_TIME, expireAt);
        if (deviceClaims != null) {
            deviceClaims.forEach((key, value) -> {
                if (value != null) {
                    claims.put(key, value);
                }
            });
        }
        if (userName != null) {
            claims.put(TokenClaims.USER_NAME, userName);
        }
        if (avatar != null) {
            claims.put(TokenClaims.AVATAR, avatar);
        }

        return Jwts.builder()
                .header().keyId(kid).and()
                .claims(claims)
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();
    }
}
