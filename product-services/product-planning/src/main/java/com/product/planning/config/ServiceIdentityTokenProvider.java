package com.product.planning.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.product.planning.common.exception.ServiceException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

/**
 * 服务身份令牌提供器（Phase 4，ADR-0003 服务身份最小实现）。
 *
 * <p>异步排程线程无请求上下文，{@code FeignForwardAuthConfig} 模式拿不到用户
 * Authorization；demand/master-data 的内部契约又要求有效签名 token（本地验签，
 * ADR-0003 两层校验）。本组件以配置的服务凭据向 Identity 内部端点换取 RS256 token，
 * 进程内缓存至过期前 {@code expireSkewMillis} 自动重签（凭据即认证；网关对
 * /internal/** 显式 404，该端点仅内网直连可达）。</p>
 *
 * <p>失败语义：Identity 不可达/凭据错误 → ServiceException（排程任务标记 FAILED），
 * 不静默降级为无 token 调用（调用必被 401 拒绝，等价失败但更快暴露配置问题）。
 * 与 {@link PlanningFeignAuthInterceptor} 的用户 token 透传共存：有请求上下文优先用户，
 * 无上下文才使用服务身份。</p>
 */
@Slf4j
@Component
public class ServiceIdentityTokenProvider {

    private final ServiceIdentityProperties properties;
    private final RestClient restClient;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 缓存的 token 与过期时间戳（epoch 毫秒；并发下重复换取无害，取最后写入者）。 */
    private volatile String cachedToken;
    private volatile long expiresAtMillis;

    public ServiceIdentityTokenProvider(ServiceIdentityProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.builder()
                .requestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory() {
                    {
                        setConnectTimeout(Duration.ofSeconds(2));
                        setReadTimeout(Duration.ofSeconds(3));
                    }
                })
                .build();
    }

    /**
     * 清除缓存（收到提供方 401 信封时调用：identity 重启换钥后旧 token 失效，重签即可恢复）。
     */
    public synchronized void evict() {
        cachedToken = null;
        expiresAtMillis = 0;
    }

    /**
     * 返回可用服务身份 token（带缓存；过期前余量自动重签）。
     */
    public synchronized String getToken() {
        if (!properties.isEnabled()) {
            throw new ServiceException("服务身份令牌未启用，无法发起服务间异步调用");
        }
        long now = System.currentTimeMillis();
        if (cachedToken != null && now < expiresAtMillis) {
            return cachedToken;
        }
        return requestNewToken(now);
    }

    private String requestNewToken(long now) {
        try {
            String body = restClient.post()
                    .uri(properties.getTokenUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(mapper.writeValueAsString(Map.of(
                            "username", properties.getUsername(),
                            "password", properties.getPassword())))
                    .retrieve()
                    .body(String.class);
            if (body == null) {
                throw new ServiceException("身份服务响应为空，无法获取服务身份令牌");
            }
            JsonNode node = mapper.readTree(body);
            if (node.path("code").asInt(-1) != 200) {
                throw new ServiceException("获取服务身份令牌失败: " + node.path("msg").asText(""));
            }
            String token = node.path("token").asText(null);
            if (token == null || token.isEmpty()) {
                throw new ServiceException("获取服务身份令牌失败: 响应缺少 token");
            }
            long expiresAt = resolveExpiry(token, now);
            cachedToken = token;
            expiresAtMillis = expiresAt;
            log.info("service identity token refreshed, expiresAt={}ms", expiresAt);
            return token;
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("service identity token request failed: url={}", properties.getTokenUrl(), e);
            throw new ServiceException("身份服务不可用，无法获取服务身份令牌，请稍后重试");
        }
    }

    /** 从 JWT payload 解析 exp（毫秒）；解析失败按 5 分钟保守缓存。 */
    private long resolveExpiry(String token, long fallbackNow) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length < 2) {
                return fallbackNow + Duration.ofMinutes(5).toMillis();
            }
            byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
            JsonNode claims = mapper.readTree(new String(payload, StandardCharsets.UTF_8));
            if (claims.hasNonNull("exp")) {
                return claims.get("exp").asLong() * 1000L - properties.getExpireSkewMillis();
            }
        } catch (Exception e) {
            log.warn("failed to parse token expiry, fallback to 5min cache", e);
        }
        return fallbackNow + Duration.ofMinutes(5).toMillis();
    }
}
