package com.product.identity.common.core.result;

import com.product.cloud.common.api.ApiStatus;

/**
 * identity 域内响应信封：为保持单体移植代码的导入路径
 * {@code com.product.common.core.result.AjaxResult} 不变而设立。
 *
 * <p>实现即微服务体系统一信封（cloud-common AjaxResult）的子类，静态工厂返回本类型，
 * JSON 序列化形状与单体/云体系信封完全一致（{code, msg, data}）。</p>
 */
public class AjaxResult extends com.product.cloud.common.api.AjaxResult {

    private static final long serialVersionUID = 1L;

    public AjaxResult() {
    }

    public AjaxResult(int code, String msg) {
        super(code, msg);
    }

    public AjaxResult(int code, String msg, Object data) {
        super(code, msg, data);
    }

    public static AjaxResult success() {
        return success("操作成功");
    }

    public static AjaxResult success(Object data) {
        return success("操作成功", data);
    }

    public static AjaxResult success(String msg) {
        return success(msg, null);
    }

    public static AjaxResult success(String msg, Object data) {
        return new AjaxResult(ApiStatus.SUCCESS, msg, data);
    }

    public static AjaxResult warn(String msg) {
        return warn(msg, null);
    }

    public static AjaxResult warn(String msg, Object data) {
        return new AjaxResult(ApiStatus.WARN, msg, data);
    }

    public static AjaxResult error() {
        return error("操作失败");
    }

    public static AjaxResult error(String msg) {
        return error(msg, null);
    }

    public static AjaxResult error(String msg, Object data) {
        return new AjaxResult(ApiStatus.ERROR, msg, data);
    }

    public static AjaxResult error(int code, String msg) {
        return new AjaxResult(code, msg, null);
    }

    @Override
    public AjaxResult put(String key, Object value) {
        super.put(key, value);
        return this;
    }
}
