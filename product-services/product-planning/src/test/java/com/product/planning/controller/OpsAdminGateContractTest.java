package com.product.planning.controller;

import org.junit.jupiter.api.Test;

import org.springframework.security.access.prepost.PreAuthorize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ops 端点管理员门禁契约测试（Phase 6 决策固化）：ops 控制器必须要求
 * {@code @ss.hasPermi('*:*:*')}（管理员），保证重放/人工改写类端点不可被普通用户
 * 或服务身份令牌（permissions 空集）调用；失败语义走单体权限契约（200 + code 403）。
 */
class OpsAdminGateContractTest {

    @Test
    void opsControllerMustRequireAdminPermission() {
        PreAuthorize gate = PlanningOpsController.class.getAnnotation(PreAuthorize.class);
        assertNotNull(gate, "PlanningOpsController 必须有类级 @PreAuthorize 管理员门禁");
        assertEquals("@ss.hasPermi('*:*:*')", gate.value());
    }
}
