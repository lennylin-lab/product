package com.product.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gateway Phase 2 契约测试（离线）：
 * 1) 统一错误体 + X-Trace-Id（Phase 1 遗留项）：404 未路由 / 503 无实例 / 401 未认证；
 * 2) 匿名端点与受保护端点的鉴权前置行为（ADR-0003 网关侧）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.cloud.nacos.discovery.enabled=false",
                "spring.cloud.nacos.config.enabled=false",
                "spring.cloud.service-registry.auto-registration.enabled=false"
        })
class GatewayApplicationTest {

    @org.springframework.boot.test.web.server.LocalServerPort
    int port;

    @Autowired
    RouteLocator routeLocator;

    @Autowired
    TestRestTemplate restTemplate;

    @Test
    void contextShouldLoadWithRouteDefinitions() {
        assertNotNull(routeLocator);
    }

    @Test
    void unroutedPathShouldReturnUnified404BodyWithTraceId() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "http://localhost:" + port + "/no-such-path", String.class);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("{\"msg\":\"请求路径不存在\",\"code\":404}", response.getBody());
        assertNotNull(response.getHeaders().getFirst("X-Trace-Id"));
    }

    @Test
    void lbRouteWithoutInstanceOnAnonymousPathShouldReturnUnified503Body() {
        // /login 是匿名端点（permit），identity 未注册实例时应得到统一 503 错误体（不再有裸 503）
        ResponseEntity<String> response = restTemplate.postForEntity(
                "http://localhost:" + port + "/login", HttpEntity.EMPTY, String.class);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("{\"msg\":\"服务暂时不可用，请稍后重试\",\"code\":503}", response.getBody());
        assertNotNull(response.getHeaders().getFirst("X-Trace-Id"));
    }

    @Test
    void protectedRouteWithoutTokenShouldReturnMonolithByteCompatible401Body() {
        // 正式业务路由（/system/**，Phase 6 收敛后 Phase 1 前缀冒烟路由已全部移除）需要有效 JWT（匿名不可达）
        ResponseEntity<String> response = restTemplate.getForEntity(
                "http://localhost:" + port + "/system/menu/list", String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("{\"msg\":\"请求访问：/system/menu/list，认证失败，无法访问系统资源\",\"code\":401}",
                response.getBody());
        assertNotNull(response.getHeaders().getFirst("X-Trace-Id"));
    }

    @Test
    void removedPhase1PrefixRoutesShouldNoLongerRoute() {
        // Phase 6 收敛：冒烟前缀路由（StripPrefix=1 的 /identity/**、/planning/** 等）已移除，
        // 仅保留各服务 api-docs 收敛路径（/identity/v3/api-docs 等）。
        // 业务前缀 /identity/skeleton/info 现在应落到统一 404 错误体。
        ResponseEntity<String> response = restTemplate.getForEntity(
                "http://localhost:" + port + "/identity/skeleton/info", String.class);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("{\"msg\":\"请求路径不存在\",\"code\":404}", response.getBody());
        assertNotNull(response.getHeaders().getFirst("X-Trace-Id"));
    }

    @Test
    void corsPreflightShouldEchoMonolithCorsPolicy() {
        WebTestClient client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        client.options().uri("/system/menu/list")
                .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.GET.name())
                .exchange()
                .expectStatus().isOk()
                // 与单体 CorsFilter（originPattern *）一致：响应回显请求 Origin
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173")
                // allowedMethods=* 时按规范回显请求方法
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, "GET");
    }
}
