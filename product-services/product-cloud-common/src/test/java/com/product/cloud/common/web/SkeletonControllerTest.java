package com.product.cloud.common.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 骨架诊断端点测试：服务标识与配置标记回显。
 */
class SkeletonControllerTest {

    @Test
    void shouldExposeServiceNameAndConfigMarkers() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.application.name", "product-identity")
                .withProperty("product.skeleton.config-marker", "nacos-identity-loaded")
                .withProperty("product.skeleton.shared-marker", "shared-config-loaded");
        SkeletonController controller = new SkeletonController(environment);

        Map<String, Object> info = controller.info();

        assertEquals("product-identity", info.get("service"));
        assertEquals(1, info.get("phase"));
        assertEquals("nacos-identity-loaded", info.get("configMarker"));
        assertEquals("shared-config-loaded", info.get("sharedMarker"));
    }

    @Test
    void shouldFallbackToUnsetWhenMarkersAbsent() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.application.name", "product-planning");
        SkeletonController controller = new SkeletonController(environment);

        Map<String, Object> info = controller.info();

        assertEquals("unset", info.get("configMarker"));
        assertEquals("unset", info.get("sharedMarker"));
    }
}
