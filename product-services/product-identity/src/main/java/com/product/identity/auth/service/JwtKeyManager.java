package com.product.identity.auth.service;

import com.product.cloud.security.jwt.Jwks;
import com.product.cloud.security.jwt.JwtSigner;
import com.product.cloud.security.jwt.RsaPem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Map;

/**
 * RS256 签名密钥管理（ADR-0003 决策 2/3：私钥仅 Identity 持有，环境 Secret 注入，不入库不入仓库）。
 *
 * <ul>
 *   <li>生产/正式环境：通过 {@code IDENTITY_JWT_PRIVATE_KEY}（PEM，PKCS#8）注入；
 *       kid 取公钥模组 SHA-256 指纹（对同一密钥稳定）。</li>
 *   <li>开发兜底：未注入时生成临时密钥对（重启后 JWKS 变化、已签发 token 全部失效，
 *       仅限本地开发），kid 随机密钥同样指纹化。</li>
 *   <li>轮换：{@code IDENTITY_JWT_PREVIOUS_PUBLIC_KEY} 注入上一把公钥，与当前公钥一并
 *       发布于 /jwks，旧 token 在 TTL 内仍可验签；仅当前密钥用于签名。</li>
 * </ul>
 */
@Slf4j
@Component
public class JwtKeyManager {

    private final PrivateKey privateKey;
    private final String kid;
    private final RSAPublicKey previousPublicKey;
    private final String previousKid;

    public JwtKeyManager(
            @Value("${product.identity.jwt.private-key:}") String privateKeyPem,
            @Value("${product.identity.jwt.previous-public-key:}") String previousPublicKeyPem) {
        if (privateKeyPem != null && !privateKeyPem.isBlank()) {
            this.privateKey = RsaPem.parsePrivateKey(privateKeyPem);
            log.info("Identity JWT 私钥已从环境注入");
        } else {
            KeyPair generated = RsaPem.generateRsaKeyPair();
            this.privateKey = generated.getPrivate();
            log.warn("未配置 product.identity.jwt.private-key，使用临时生成的开发密钥（重启后已签发 token 失效）");
        }
        this.kid = Jwks.kidOf((RSAPublicKey) RsaPem.derivePublicKey(this.privateKey));
        if (previousPublicKeyPem != null && !previousPublicKeyPem.isBlank()) {
            this.previousPublicKey = (RSAPublicKey) RsaPem.parsePublicKey(previousPublicKeyPem);
            this.previousKid = Jwks.kidOf(this.previousPublicKey);
            log.info("Identity JWKS 发布轮换窗口公钥 kid={}", this.previousKid);
        } else {
            this.previousPublicKey = null;
            this.previousKid = null;
        }
    }

    public JwtSigner signer() {
        return new JwtSigner(privateKey, kid);
    }

    public String kid() {
        return kid;
    }

    /** JWKS（当前公钥 + 可选轮换窗口公钥）。 */
    public Map<String, Object> jwks() {
        java.util.List<Map<String, Object>> keys = new java.util.ArrayList<>();
        keys.add(Jwks.toJwk((RSAPublicKey) RsaPem.derivePublicKey(privateKey), kid));
        if (previousPublicKey != null) {
            keys.add(Jwks.toJwk(previousPublicKey, previousKid));
        }
        return Jwks.keySet(keys);
    }
}
