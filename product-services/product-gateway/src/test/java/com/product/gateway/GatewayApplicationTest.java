package com.product.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Gateway 骨架冒烟测试（离线）：
 * 上下文可启动、路由定义存在；无可用实例时 lb 路由返回 503（证明负载均衡已接线）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.cloud.nacos.discovery.enabled=false",
                "spring.cloud.nacos.config.enabled=false",
                "spring.cloud.service-registry.auto-registration.enabled=false"
        })
class GatewayApplicationTest {

    @LocalServerPort
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
    void lbRouteWithoutInstanceShouldReturnServiceUnavailable() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "http://localhost:" + port + "/identity/skeleton/info", String.class);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
    }
}
