package com.product.cloud.security.jwt;

import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RS256 签发/验签核心测试（ADR-0003）。全程使用本地生成的密钥对，离线可跑。
 */
class JwtSignerVerifierTest {

    private final JwtVerifier verifier = new JwtVerifier(0);

    private KeyPair keyPair() {
        return RsaPem.generateRsaKeyPair();
    }

    @Test
    void signShouldEmbedKidIssuerExpAndMonolithClaims() throws Exception {
        KeyPair pair = keyPair();
        RSAPublicKey publicKey = (RSAPublicKey) pair.getPublic();
        JwtSigner signer = new JwtSigner(pair.getPrivate(), Jwks.kidOf(publicKey));

        String token = signer.sign("admin", 1L, List.of("*:*:*"),
                Map.of("ipaddr", "127.0.0.1", "browser", "Chrome"), "admin", "", 300);

        VerifiedToken verified = verifier.verify(token, publicKey);
        assertEquals(Jwks.kidOf(publicKey), verified.kid());
        assertEquals("product-identity", verified.claims().getIssuer());
        assertEquals("admin", verified.claims().getSubject());
        assertEquals(1L, ((Number) verified.claims().get(TokenClaims.USER_ID)).longValue());
        assertEquals(List.of("*:*:*"), verified.claims().get(TokenClaims.PERMISSIONS));
        assertNotNull(verified.claims().getId());
        // exp（标准，秒精度）与单体自定义 expireTime（毫秒）为同一时刻
        assertTrue(Math.abs(verified.claims().getExpiration().getTime()
                - ((Number) verified.claims().get(TokenClaims.EXPIRE_TIME)).longValue()) < 1000);
        assertEquals(verified.claims().get(TokenClaims.BROWSER), "Chrome");
    }

    @Test
    void forgedTokenShouldFailVerification() {
        KeyPair signerPair = keyPair();
        KeyPair attackerPair = keyPair();
        JwtSigner signer = new JwtSigner(signerPair.getPrivate(), "kid-1");
        String token = signer.sign("admin", 1L, List.of("*:*:*"), Map.of(), "admin", "", 300);

        assertThrows(JwtVerificationException.class,
                () -> verifier.verify(token, attackerPair.getPublic()));
    }

    @Test
    void tamperedPayloadShouldFailVerification() {
        KeyPair pair = keyPair();
        JwtSigner signer = new JwtSigner(pair.getPrivate(), "kid-1");
        String token = signer.sign("admin", 1L, List.of("*:*:*"), Map.of(), "admin", "", 300);
        String[] parts = token.split("\\.");
        String tampered = parts[0] + ".eyJzdWIiOiJoYWNrZXIifQ." + parts[2];

        assertThrows(JwtVerificationException.class, () -> verifier.verify(tampered, pair.getPublic()));
    }

    @Test
    void expiredTokenShouldFailVerification() throws Exception {
        KeyPair pair = keyPair();
        JwtSigner signer = new JwtSigner(pair.getPrivate(), "kid-1");
        // TTL 为 0：签发即过期（iat=now, exp=now）
        String token = signer.sign("admin", 1L, List.of(), Map.of(), "admin", "", 0);

        JwtVerificationException exception = assertThrows(JwtVerificationException.class,
                () -> verifier.verify(token, pair.getPublic()));
        assertTrue(exception.getMessage() != null);
    }

    @Test
    void jwkRoundTripShouldKeepSamePublicKey() {
        KeyPair pair = keyPair();
        RSAPublicKey publicKey = (RSAPublicKey) pair.getPublic();
        String kid = Jwks.kidOf(publicKey);

        Map<String, Object> jwk = Jwks.toJwk(publicKey, kid);
        assertEquals("RSA", jwk.get("kty"));
        assertEquals("sig", jwk.get("use"));
        assertEquals("RS256", jwk.get("alg"));
        assertEquals(kid, jwk.get("kid"));

        RSAPublicKey restored = JwtVerifier.toRsaPublicKey(jwk);
        assertEquals(publicKey, restored);

        // kid 确定性：同一密钥重复计算结果一致；不同密钥结果不同
        assertEquals(kid, Jwks.kidOf(restored));
        assertNotEquals(kid, Jwks.kidOf((RSAPublicKey) keyPair().getPublic()));
    }

    @Test
    void pemRoundTripShouldReproduceKeys() {
        KeyPair pair = keyPair();
        PrivateKey restoredPrivate = RsaPem.parsePrivateKey(RsaPem.toPrivateKeyPem(pair.getPrivate()));
        RSAPublicKey restoredPublic = (RSAPublicKey) RsaPem.parsePublicKey(RsaPem.toPublicKeyPem(pair.getPublic()));

        assertEquals(pair.getPrivate(), restoredPrivate);
        assertEquals(pair.getPublic(), restoredPublic);
        // CRT 私钥可推导公钥（identity 只注入私钥场景）
        assertEquals(pair.getPublic(), RsaPem.derivePublicKey(restoredPrivate));
    }
}
