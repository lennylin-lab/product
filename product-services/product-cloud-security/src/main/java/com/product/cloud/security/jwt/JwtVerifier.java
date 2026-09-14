package com.product.cloud.security.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.SignatureException;

import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/**
 * JWT 本地验签器（纯 Java，无 Web 依赖；Gateway 与各服务共用，ADR-0003 决策 4）。
 *
 * <p>两层校验语义：</p>
 * <ul>
 *   <li>RS256 签名验证（仅接受 RS256，拒绝其它 alg，防 alg 混淆）；</li>
 *   <li>标准 {@code exp} 校验（jjwt 内建）——单体 HS512 自包含 token 实际未强制过期的
 *       现状在微服务体系按 ADR-0003 决策 5 收敛为强制过期；</li>
 *   <li>自定义 {@code expireTime} claim 与标准 {@code exp} 同值双校验。</li>
 * </ul>
 *
 * <p>验签失败/过期/格式非法一律抛出 {@link JwtVerificationException}，由调用方决定
 * HTTP 契约（网关 401 统一错误体 / 服务端 401 统一错误体）。</p>
 */
public class JwtVerifier {

    private static final String RS256 = "RS256";

    /** 允许的时钟偏移（秒）：网关与服务可能存在毫秒级时钟差。 */
    private final long clockSkewSeconds;

    public JwtVerifier() {
        this(60);
    }

    public JwtVerifier(long clockSkewSeconds) {
        this.clockSkewSeconds = clockSkewSeconds;
    }

    /**
     * 用指定公钥验签并解析。
     *
     * @param token     JWT 紧凑串（不含 Bearer 前缀）
     * @param publicKey token header kid 对应的 RSA 公钥
     * @return 已验证的 claims
     * @throws JwtVerificationException 签名不匹配、过期、格式非法、alg 非 RS256
     */
    public VerifiedToken verify(String token, PublicKey publicKey) throws JwtVerificationException {
        if (publicKey instanceof RSAPublicKey rsaKey) {
            try {
                var jws = Jwts.parser()
                        .clockSkewSeconds(clockSkewSeconds)
                        .verifyWith(rsaKey)
                        .build()
                        .parseSignedClaims(token);
                if (!RS256.equalsIgnoreCase(jws.getHeader().getAlgorithm())) {
                    throw new JwtVerificationException("仅接受 RS256 签名算法");
                }
                return new VerifiedToken((String) jws.getHeader().get("kid"), jws.getPayload());
            } catch (SignatureException e) {
                throw new JwtVerificationException("token 签名验证失败", e);
            } catch (JwtException e) {
                throw new JwtVerificationException("token 无效或已过期", e);
            } catch (IllegalArgumentException e) {
                throw new JwtVerificationException("token 格式非法", e);
            }
        }
        throw new JwtVerificationException("验签公钥类型不支持");
    }

    /**
     * 自定义 expireTime claim 二次校验（与标准 exp 同值；单体 claims 结构保持）。
     */
    public void verifyExpireClaim(Claims claims) throws JwtVerificationException {
        Object expireTime = claims.get(TokenClaims.EXPIRE_TIME);
        if (expireTime instanceof Number seconds && seconds.longValue() > 0) {
            if (Instant.now().getEpochSecond() * 1000L > seconds.longValue() + clockSkewSeconds * 1000L) {
                throw new JwtVerificationException("token 已过期");
            }
        }
    }

    /** 从 claims 还原权限集合（单体 buildLoginUser 的 List→Set 语义）。 */
    public static Set<String> extractPermissions(Claims claims) {
        Object raw = claims.get(TokenClaims.PERMISSIONS);
        Set<String> permissions = new HashSet<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    permissions.add(item.toString());
                }
            }
        }
        return permissions;
    }

    /** claims → 调试摘要（不含敏感信息）。 */
    public static String describe(Claims claims) {
        return String.format("subject=%s, userId=%s, issuedAt=%s, expiresAt=%s",
                claims.getSubject(),
                claims.get(TokenClaims.USER_ID),
                Date.from(claims.getIssuedAt().toInstant()),
                Date.from(claims.getExpiration().toInstant()));
    }

    /** JWK map（来自 JWKS 端点）→ RSAPublicKey。 */
    public static RSAPublicKey toRsaPublicKey(Map<String, Object> jwk) {
        if (!"RSA".equals(jwk.get("kty"))) {
            throw new IllegalArgumentException("仅支持 kty=RSA 的 JWK");
        }
        var spec = new java.security.spec.RSAPublicKeySpec(
                new java.math.BigInteger(1, Jwks.decodeBase64Url(String.valueOf(jwk.get("n")))),
                new java.math.BigInteger(1, Jwks.decodeBase64Url(String.valueOf(jwk.get("e")))));
        try {
            return (RSAPublicKey) java.security.KeyFactory.getInstance("RSA").generatePublic(spec);
        } catch (Exception e) {
            throw new IllegalStateException("JWK 转 RSA 公钥失败", e);
        }
    }
}
