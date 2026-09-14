package com.product.cloud.common.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AjaxResult 信封契约测试：与单体现有 {code, msg, data} 线上格式保持一致。
 */
class AjaxResultTest {

    @Test
    void successShouldCarryCode200MsgAndData() {
        AjaxResult result = AjaxResult.success("操作成功", "payload");
        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        assertEquals("操作成功", result.get(AjaxResult.MSG_TAG));
        assertEquals("payload", result.get(AjaxResult.DATA_TAG));
        assertTrue(result.isSuccess());
    }

    @Test
    void successWithoutDataShouldOmitDataKey() {
        AjaxResult result = AjaxResult.success();
        assertTrue(result.containsKey(AjaxResult.CODE_TAG));
        assertTrue(result.containsKey(AjaxResult.MSG_TAG));
        assertFalse(result.containsKey(AjaxResult.DATA_TAG));
    }

    @Test
    void errorShouldDefaultToCode500() {
        AjaxResult result = AjaxResult.error("订单不存在");
        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("订单不存在", result.get(AjaxResult.MSG_TAG));
        assertTrue(result.isError());
    }

    @Test
    void errorWithCustomCodeShouldKeepProvidedCode() {
        AjaxResult result = AjaxResult.error(403, "没有权限，请联系管理员授权");
        assertEquals(403, result.get(AjaxResult.CODE_TAG));
    }

    @Test
    void warnShouldUseCode601() {
        AjaxResult result = AjaxResult.warn("警告信息");
        assertEquals(601, result.get(AjaxResult.CODE_TAG));
        assertTrue(result.isWarn());
    }
}
