package com.product.cloud.security.jwks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.product.cloud.security.jwt.JwtVerifier;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.PublicKey;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * JWKS 公钥持有者（Gateway 与各服务共用，ADR-0003 决策 3）。
 *
 * <p>从 Identity 的 {@code GET /jwks} 拉取 JWK Set，按 header {@code kid} 匹配公钥并缓存；
 * 遇到未知 kid（轮换后签发的新 token）时自动刷新一次再匹配，支持新旧 key 并存的轮换窗口。
 * 拉取失败时保留上次成功的缓存（Identity 短暂不可用不影响已缓存公钥的本地验签）。</p>
 */
public class JwksKeyHolder {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String jwksUri;
    private final HttpClient httpClient;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final Map<String, PublicKey> keyCache = new HashMap<>();

    public JwksKeyHolder(String jwksUri) {
        this(jwksUri, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    }

    public JwksKeyHolder(String jwksUri, HttpClient httpClient) {
        this.jwksUri = jwksUri;
        this.httpClient = httpClient;
    }

    /**
     * 按 kid 取公钥；本地无此 kid 时强制刷新一次 JWKS 再取。
     *
     * @return 公钥；无法取得（JWKS 不可达且无缓存、kid 仍未知）返回 null
     */
    public PublicKey forKeyId(String kid) {
        if (kid == null || kid.isBlank()) {
            return null;
        }
        PublicKey cached = readCache(kid);
        if (cached != null) {
            return cached;
        }
        refresh();
        return readCache(kid);
    }

    /** 当前缓存的 key 数量（探测/测试用）。 */
    public int cacheSize() {
        lock.readLock().lock();
        try {
            return keyCache.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    /** 拉取 JWKS 并重建缓存；成功则返回 true，失败（网络/格式）保留旧缓存并返回 false。 */
    public boolean refresh() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(jwksUri))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return false;
            }
            Map<String, PublicKey> refreshed = new HashMap<>();
            JsonNode keys = MAPPER.readTree(response.body()).path("keys");
            for (JsonNode jwk : keys) {
                String kid = jwk.path("kid").asText(null);
                if (kid != null && !kid.isBlank()) {
                    refreshed.put(kid, JwtVerifier.toRsaPublicKey(MAPPER.convertValue(jwk, Map.class)));
                }
            }
            if (refreshed.isEmpty()) {
                return false;
            }
            lock.writeLock().lock();
            try {
                keyCache.clear();
                keyCache.putAll(refreshed);
            } finally {
                lock.writeLock().unlock();
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private PublicKey readCache(String kid) {
        lock.readLock().lock();
        try {
            return keyCache.get(kid);
        } finally {
            lock.readLock().unlock();
        }
    }
}
