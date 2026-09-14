package com.product.cloud.security.jwt;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * RSA PEM（PKCS#8 私钥 / X.509 公钥）编解码与密钥生成工具（ADR-0003）。
 *
 * <p>Identity 的签名私钥通过环境变量注入（PEM 文本），不入库不入仓库；
 * 公钥经 {@code GET /jwks} 以 JWK Set 形式发布。</p>
 */
public final class RsaPem {

    private static final Base64.Decoder DECODER = Base64.getMimeDecoder();
    private static final Base64.Encoder ENCODER = Base64.getEncoder();

    private RsaPem() {
    }

    /** 解析 PKCS#8 PEM 私钥（忽略头尾与空白）。 */
    public static PrivateKey parsePrivateKey(String pem) {
        byte[] der = decodePem(pem, "PRIVATE KEY");
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (InvalidKeySpecException | NoSuchAlgorithmException e) {
            throw new IllegalArgumentException("无效的 RSA 私钥 PEM", e);
        }
    }

    /** 解析 X.509 PEM 公钥。 */
    public static PublicKey parsePublicKey(String pem) {
        byte[] der = decodePem(pem, "PUBLIC KEY");
        try {
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (InvalidKeySpecException | NoSuchAlgorithmException e) {
            throw new IllegalArgumentException("无效的 RSA 公钥 PEM", e);
        }
    }

    /** 私钥 → PEM（PKCS#8）。 */
    public static String toPrivateKeyPem(PrivateKey key) {
        return "-----BEGIN PRIVATE KEY-----\n" + ENCODER.encodeToString(key.getEncoded()) + "\n-----END PRIVATE KEY-----";
    }

    /** 公钥 → PEM（X.509）。 */
    public static String toPublicKeyPem(PublicKey key) {
        return "-----BEGIN PUBLIC KEY-----\n" + ENCODER.encodeToString(key.getEncoded()) + "\n-----END PUBLIC KEY-----";
    }

    /** 由 CRT 私钥推导对应公钥（省去重复配置公钥）。 */
    public static RSAPublicKey derivePublicKey(PrivateKey privateKey) {
        if (!(privateKey instanceof RSAPrivateCrtKey crtKey)) {
            throw new IllegalArgumentException("仅支持 RSAPrivateCrtKey（PKCS#8 RSA 私钥）");
        }
        RSAPublicKeySpec spec = new RSAPublicKeySpec(crtKey.getModulus(), crtKey.getPublicExponent());
        try {
            return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(spec);
        } catch (InvalidKeySpecException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("从私钥推导公钥失败", e);
        }
    }

    /** 生成 2048 位 RSA 密钥对（开发期兜底，见 JwtSignKeyManager）。 */
    public static KeyPair generateRsaKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048, new SecureRandom());
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 RSA 实现", e);
        }
    }

    private static byte[] decodePem(String pem, String expectedLabel) {
        if (pem == null || pem.isBlank()) {
            throw new IllegalArgumentException("PEM 内容为空");
        }
        String body = pem.replace("-----BEGIN " + expectedLabel + "-----", "")
                .replace("-----END " + expectedLabel + "-----", "")
                .replaceAll("\\s", "");
        return DECODER.decode(body);
    }
}
