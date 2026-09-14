package com.product.cloud.security.jwt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RSA 公钥 → JWK / JWK Set（RFC 7517）编码，供 Identity 的 {@code GET /jwks} 端点使用。
 *
 * <p>{@code kid} 取公钥模组 SHA-256 指纹前 16 位十六进制（确定性：同一密钥无论注入格式如何，
 * kid 稳定不变），验签方按 header kid 匹配公钥并支持轮换窗口内新旧 key 并存（ADR-0003）。</p>
 */
public final class Jwks {

    private Jwks() {
    }

    /** 单个 RSA 公钥 → JWK map（kty/use/alg/kid/n/e）。 */
    public static Map<String, Object> toJwk(RSAPublicKey key, String kid) {
        Map<String, Object> jwk = new LinkedHashMap<>();
        jwk.put("kty", "RSA");
        jwk.put("use", "sig");
        jwk.put("alg", "RS256");
        jwk.put("kid", kid != null && !kid.isBlank() ? kid : kidOf(key));
        jwk.put("n", base64UrlUnsigned(key.getModulus().toByteArray()));
        jwk.put("e", base64UrlUnsigned(key.getPublicExponent().toByteArray()));
        return jwk;
    }

    /** JWK Set map：{@code {"keys":[...]}}。 */
    public static Map<String, Object> keySet(Iterable<Map<String, Object>> jwks) {
        Map<String, Object> set = new LinkedHashMap<>();
        set.put("keys", jwks);
        return set;
    }

    /**
     * 公钥指纹 kid：SHA-256(modulus) 前 16 位十六进制。
     */
    public static String kidOf(RSAPublicKey key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getModulus().toByteArray());
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256 实现", e);
        }
    }

    /** 大整数（含符号位）→ base64url 无符号编码（RFC 7518 §6.3.1.1）。 */
    private static String base64UrlUnsigned(byte[] bytes) {
        int index = 0;
        while (index < bytes.length - 1 && bytes[index] == 0) {
            index++;
        }
        byte[] unsigned = new byte[bytes.length - index];
        System.arraycopy(bytes, index, unsigned, 0, unsigned.length);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(unsigned)
                .replace("+", "-").replace("/", "_");
    }

    /** base64url 解码（容忍 padding 缺失）。 */
    public static byte[] decodeBase64Url(String value) {
        String normalized = value.replace("-", "+").replace("_", "/");
        int padding = (4 - normalized.length() % 4) % 4;
        return Base64.getDecoder().decode(normalized + "=".repeat(padding));
    }

    /** UTF-8 helper（保持本工具类自包含）。 */
    public static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
