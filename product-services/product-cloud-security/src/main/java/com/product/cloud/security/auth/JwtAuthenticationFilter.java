package com.product.cloud.security.auth;

import com.product.cloud.security.jwks.JwksKeyHolder;
import com.product.cloud.security.jwt.JwtVerificationException;
import com.product.cloud.security.jwt.JwtVerifier;
import com.product.cloud.security.jwt.VerifiedToken;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * JWT 本地验签过滤器（{@code JwtAuthenticationTokenFilter} 的微服务等价物，ADR-0003 决策 4）。
 *
 * <p>从 {@code Authorization: Bearer <token>} 提取 token，按 header kid 从
 * {@link JwksKeyHolder} 取得公钥本地验签（RS256 + exp + expireTime 双校验），重建
 * {@link PrincipalUser} 并写入 SecurityContext 与 MDC（userId/username，与单体过滤器一致）。</p>
 *
 * <p>防伪语义：服务只信任验签通过的 token 重建的身份，不读取任何明文内部头
 * （X-User-Id 之类由网关在入口剥离/覆盖）；无 token 或验签失败时不阻断请求，
 * 交由 SecurityFilterChain 对受保护路径返回统一 401 错误体（与单体行为一致）。</p>
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String TOKEN_PREFIX = "Bearer ";

    private final JwtVerifier jwtVerifier;
    private final JwksKeyHolder keyHolder;
    private final String headerName;

    public JwtAuthenticationFilter(JwtVerifier jwtVerifier, JwksKeyHolder keyHolder, String headerName) {
        this.jwtVerifier = jwtVerifier;
        this.keyHolder = keyHolder;
        this.headerName = headerName;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = extractToken(request);
        if (StringUtils.hasText(token) && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                VerifiedToken verified = verifyByKid(token);
                jwtVerifier.verifyExpireClaim(verified.claims());
                PrincipalUser principalUser = new PrincipalUser(verified.claims(),
                        JwtVerifier.extractPermissions(verified.claims()));
                UsernamePasswordAuthenticationToken authenticationToken = new UsernamePasswordAuthenticationToken(
                        principalUser, null, principalUser.getAuthorities());
                authenticationToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authenticationToken);
                if (principalUser.getUserId() != null) {
                    MDC.put("userId", String.valueOf(principalUser.getUserId()));
                }
                if (StringUtils.hasLength(principalUser.getUsername())) {
                    MDC.put("username", principalUser.getUsername());
                }
            } catch (JwtVerificationException e) {
                // 与单体 JwtAuthenticationTokenFilter 一致：验签失败仅按匿名继续（受保护路径由 401 entry point 兜底）
                MDC.remove("userId");
                MDC.remove("username");
            }
        }
        // 说明：与单体一致，MDC 的 userId/username 不在安全过滤器内清理，
        // 由请求最外层的 RequestContextFilter 统一 MDC.clear()，避免线程污染。
        chain.doFilter(request, response);
    }

    private VerifiedToken verifyByKid(String token) throws JwtVerificationException {
        // 先按 header kid 找公钥；kid 缺失时尝试逐个缓存公钥（兼容无 kid 的过渡 token）
        String kidHint = headerKid(token);
        java.security.PublicKey publicKey = keyHolder.forKeyId(kidHint);
        if (publicKey != null) {
            return jwtVerifier.verify(token, publicKey);
        }
        throw new JwtVerificationException("无法取得验签公钥（JWKS 不可达或未知 kid）");
    }

    /** 未验签前仅解析 header 的 kid（不校验签名，纯 base64 解码，用于选钥）。 */
    private String headerKid(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                return null;
            }
            String headerJson = new String(com.product.cloud.security.jwt.Jwks.decodeBase64Url(parts[0]),
                    java.nio.charset.StandardCharsets.UTF_8);
            if (headerJson.contains("\"kid\"")) {
                com.fasterxml.jackson.databind.JsonNode node =
                        new com.fasterxml.jackson.databind.ObjectMapper().readTree(headerJson);
                return node.path("kid").asText(null);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private String extractToken(HttpServletRequest request) {
        String token = request.getHeader(headerName);
        if (StringUtils.hasText(token) && token.startsWith(TOKEN_PREFIX)) {
            return token.substring(TOKEN_PREFIX.length());
        }
        return token;
    }
}
