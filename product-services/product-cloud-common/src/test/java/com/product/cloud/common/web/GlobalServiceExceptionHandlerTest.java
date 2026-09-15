package com.product.cloud.common.web;

import com.product.cloud.common.api.AjaxResult;
import com.product.cloud.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 统一错误契约映射测试：与现有单体 GlobalExceptionHandler 语义一致。
 */
class GlobalServiceExceptionHandlerTest {

    private final GlobalServiceExceptionHandler handler = new GlobalServiceExceptionHandler();

    private MockHttpServletRequest request() {
        return new MockHttpServletRequest("GET", "/demand/order/1");
    }

    @Test
    void serviceExceptionWithoutCodeShouldReturnCode500() {
        AjaxResult result = handler.handleServiceException(new ServiceException("订单不存在"), request());
        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("订单不存在", result.get(AjaxResult.MSG_TAG));
    }

    @Test
    void serviceExceptionWithCustomCodeShouldReturnProvidedCode() {
        AjaxResult result = handler.handleServiceException(new ServiceException("自定义失败", 601), request());
        assertEquals(601, result.get(AjaxResult.CODE_TAG));
        assertEquals("自定义失败", result.get(AjaxResult.MSG_TAG));
    }

    @Test
    void accessDeniedShouldReturnCode403WithUnifiedMessage() {
        AjaxResult result = handler.handleAccessDeniedException(
                new AccessDeniedException("denied"), request());
        assertEquals(403, result.get(AjaxResult.CODE_TAG));
        assertEquals("没有权限，请联系管理员授权", result.get(AjaxResult.MSG_TAG));
    }

    @Test
    void runtimeExceptionShouldReturnCode500WithMessage() {
        AjaxResult result = handler.handleRuntimeException(new IllegalStateException("boom"), request());
        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("boom", result.get(AjaxResult.MSG_TAG));
    }

    @Test
    void noResourceFoundShouldReturnCode404WithUnifiedMessage() {
        // issue #5：服务内路由已匹配但端点缺失 → 统一 404 语义（与网关 404 同文案），不再 500
        AjaxResult result = handler.handleNoResourceFound(
                new org.springframework.web.servlet.resource.NoResourceFoundException(
                        org.springframework.http.HttpMethod.GET, "register"),
                request());
        assertEquals(404, result.get(AjaxResult.CODE_TAG));
        assertEquals("请求路径不存在", result.get(AjaxResult.MSG_TAG));
    }

    @Test
    void noHandlerFoundShouldReturnCode404WithUnifiedMessage() {
        AjaxResult result = handler.handleNoResourceFound(
                new org.springframework.web.servlet.NoHandlerFoundException(
                        "POST", "/register", null),
                request());
        assertEquals(404, result.get(AjaxResult.CODE_TAG));
        assertEquals("请求路径不存在", result.get(AjaxResult.MSG_TAG));
    }

    @Test
    void methodArgumentNotValidShouldReturnFirstFieldErrorMessage() throws NoSuchMethodException {
        BeanPropertyBindingResult bindingResult =
                new BeanPropertyBindingResult(new Object(), "order");
        bindingResult.addError(new FieldError("order", "quantity", "数量不能为空"));
        MethodArgumentNotValidException e =
                new MethodArgumentNotValidException(sampleMethodParameter(), bindingResult);
        AjaxResult result = handler.handleMethodArgumentNotValidException(e);
        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("数量不能为空", result.get(AjaxResult.MSG_TAG));
    }

    /** MethodArgumentNotValidException 需要真实的 MethodParameter（构造/消息构建会读取参数元数据）。 */
    private org.springframework.core.MethodParameter sampleMethodParameter() throws NoSuchMethodException {
        return new org.springframework.core.MethodParameter(
                GlobalServiceExceptionHandlerTest.class.getDeclaredMethod("sampleMethod", String.class), 0);
    }

    @SuppressWarnings("unused")
    private static void sampleMethod(String quantity) {
    }

    @Test
    void typeMismatchShouldReturnFriendlyChineseMessage() {
        MethodArgumentTypeMismatchException e =
                new MethodArgumentTypeMismatchException("abc", Long.class, "id", null, new NumberFormatException());
        AjaxResult result = handler.handleMethodArgumentTypeMismatchException(e, request());
        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        // 与单体 GlobalExceptionHandler 相同：RequiredType.getName()（不带 "class " 前缀）
        assertEquals("请求参数类型不匹配，参数[id]要求类型为：'java.lang.Long'", result.get(AjaxResult.MSG_TAG));
    }
}
