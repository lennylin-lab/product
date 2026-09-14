package com.product.execution.controller;

import org.junit.jupiter.api.Test;

import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * ops 端点管理员门禁契约测试（Phase 6 决策固化）：执行域 {@code /ops/*} 运维端点必须
 * 要求 {@code @ss.hasPermi('*:*:*')}；资源事件契约端点保持"有效签名 token"契约不变
 * （服务间调用经此通道，不走管理员门禁）。
 */
class OpsAdminGateContractTest {

    @Test
    void opsEndpointsMustRequireAdminPermission() {
        for (String name : new String[] {"outboxInspection", "replayOutbox"}) {
            Method method = findMethod(name);
            PreAuthorize gate = method.getAnnotation(PreAuthorize.class);
            assertNotNull(gate, "/ops/" + name + " 必须有 @PreAuthorize 管理员门禁");
            assertEquals("@ss.hasPermi('*:*:*')", gate.value());
        }
    }

    @Test
    void contractEndpointsMustStayTokenOnly() {
        for (String name : new String[] {"recordResourceStatusEvent", "listResourceStatusEvents"}) {
            Method method = findMethod(name);
            assertNull(method.getAnnotation(PreAuthorize.class),
                    "契约端点 " + name + " 必须保持 token 契约（不加管理员门禁）");
        }
    }

    private Method findMethod(String name) {
        for (Method method : InternalExecutionController.class.getDeclaredMethods()) {
            if (method.getName().equals(name)) {
                return method;
            }
        }
        throw new IllegalStateException("method not found: " + name);
    }
}
