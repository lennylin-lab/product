package com.product.cloud.security.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.product.cloud.security.jwks.JwksKeyHolder;
import com.product.cloud.security.jwt.Jwks;
import com.product.cloud.security.jwt.JwtSigner;
import com.product.cloud.security.jwt.JwtVerifier;
import com.product.cloud.security.jwt.RsaPem;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.interfaces.RSAPublicKey;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 服务端本地验签链测试：JWKS 拉取 + 过滤器重建认证 + 401 错误体逐字节契约。
 */
class JwtAuthenticationChainTest {

    private HttpServer jwksServer;
    private String jwksUrl;
    private KeyPair keyPair;
    private String kid;
    private JwtSigner signer;

    @BeforeEach
    void setUp() throws IOException {
        keyPair = RsaPem.generateRsaKeyPair();
        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
        kid = Jwks.kidOf(publicKey);
        signer = new JwtSigner(keyPair.getPrivate(), kid);

        // 内嵌 HTTP 服务模拟 Identity 的 GET /jwks（离线回环地址）
        Map<String, Object> jwks = Jwks.keySet(List.of(Jwks.toJwk(publicKey, kid)));
        byte[] body = new ObjectMapper().writeValueAsBytes(jwks);
        jwksServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        jwksServer.createContext("/jwks", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        jwksServer.start();
        jwksUrl = "http://127.0.0.1:" + jwksServer.getAddress().getPort() + "/jwks";
    }

    @AfterEach
    void tearDown() {
        if (jwksServer != null) {
            jwksServer.stop(0);
        }
        SecurityContextHolder.clearContext();
        org.slf4j.MDC.clear();
    }

    @Test
    void filterShouldRebuildPrincipalFromValidToken() throws Exception {
        JwksKeyHolder holder = new JwksKeyHolder(jwksUrl);
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(new JwtVerifier(0), holder, "Authorization");

        String token = signer.sign("admin", 1L, List.of("*:*:*"), Map.of("browser", "Chrome"), "admin", "", 300);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/system/menu/list");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] reached = {false};
        filter.doFilter(request, response, (req, res) -> reached[0] = true);

        assertTrue(reached[0]);
        PrincipalUser principal = (PrincipalUser) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        assertEquals(1L, principal.getUserId());
        assertEquals("admin", principal.getUsername());
        assertEquals(Set.of("*:*:*"), principal.getPermissions());
        assertEquals("Chrome", principal.getBrowser());
        assertEquals("1", org.slf4j.MDC.get("userId"));
    }

    @Test
    void filterShouldLeaveAnonymousContextForInvalidToken() throws Exception {
        JwksKeyHolder holder = new JwksKeyHolder(jwksUrl);
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(new JwtVerifier(0), holder, "Authorization");

        // 正确 kid 结构但被伪造的签名（用另一把密钥签发，kid 伪装成真 kid 也不会匹配签名）
        KeyPair attacker = RsaPem.generateRsaKeyPair();
        String forged = new JwtSigner(attacker.getPrivate(), kid)
                .sign("admin", 1L, List.of("*:*:*"), Map.of(), "admin", "", 300);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/system/menu/list");
        request.addHeader("Authorization", "Bearer " + forged);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> { });

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void entryPointShouldReturnMonolithByteCompatibleBody() throws Exception {
        ProductAuthenticationEntryPoint entryPoint = new ProductAuthenticationEntryPoint();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/system/menu/list");
        MockHttpServletResponse response = new MockHttpServletResponse();

        entryPoint.commence(request, response, new org.springframework.security.authentication.BadCredentialsException("x"));

        // 与单体 ServletUtils.renderString + fastjson2 序列化逐字节一致：
        // HTTP 200 + AjaxResult(HashMap) 迭代键序 msg→code（Jackson 与 fastjson2 对同一 HashMap 的迭代一致）
        assertEquals(200, response.getStatus());
        assertTrue(response.getContentType().startsWith("application/json"));
        String expected = "{\"msg\":\"请求访问：/system/menu/list，认证失败，无法访问系统资源\",\"code\":401}";
        assertEquals(expected, response.getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void jwksKeyHolderShouldRefreshOnUnknownKid() {
        JwksKeyHolder holder = new JwksKeyHolder(jwksUrl);
        // 第一次取 kid：触发拉取并缓存
        assertNotNull(holder.forKeyId(kid));
        assertEquals(1, holder.cacheSize());
        // 未知 kid：刷新后仍不存在，返回 null（网关/服务按 401 处理）
        assertNull(holder.forKeyId("unknown-kid"));
    }

    @Test
    void permissionServiceShouldMirrorMonolithSemantics() {
        PermissionService ss = new PermissionService();
        // 无认证主体 → 与单体 SecurityUtils.getLoginUser 相同的 401 业务异常
        org.junit.jupiter.api.Assertions.assertThrows(
                com.product.cloud.common.exception.ServiceException.class, () -> ss.hasPermi("system:menu:list"));

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationTokenForTest(
                        new PrincipalUser(verifiedClaims("admin", 1L, List.of("*:*:*")), Set.of("*:*:*"))));
        assertTrue(ss.hasPermi("system:menu:list"));
        assertTrue(ss.hasPermi("tool:gen:list"));

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationTokenForTest(
                        new PrincipalUser(verifiedClaims("user", 2L, List.of()), Set.of())));
        org.junit.jupiter.api.Assertions.assertFalse(ss.hasPermi("system:menu:list"));
        org.junit.jupiter.api.Assertions.assertFalse(ss.hasRole("admin"));
    }

    private io.jsonwebtoken.Claims verifiedClaims(String subject, long userId, List<String> permissions) {
        // 直接构造已验证 claims（PrincipalUser 只读取字段，无需再次签名验证）
        KeyPair pair = RsaPem.generateRsaKeyPair();
        String token = new JwtSigner(pair.getPrivate(), "kid-t")
                .sign(subject, userId, permissions, Map.of(), subject, "", 300);
        try {
            return new JwtVerifier(0).verify(token, pair.getPublic()).claims();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 最小 Authentication 实现（测试用）。 */
    static final class UsernamePasswordAuthenticationTokenForTest
            extends org.springframework.security.authentication.UsernamePasswordAuthenticationToken {
        UsernamePasswordAuthenticationTokenForTest(Object principal) {
            super(principal, null);
        }

        @Override
        public boolean isAuthenticated() {
            return true;
        }
    }
}
