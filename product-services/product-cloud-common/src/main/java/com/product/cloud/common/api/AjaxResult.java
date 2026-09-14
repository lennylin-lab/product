package com.product.cloud.common.api;

import java.util.HashMap;
import java.util.Objects;

/**
 * 操作消息提醒（微服务体系统一错误契约的响应信封）。
 *
 * <p>与现有单体 {@code com.product.common.core.result.AjaxResult} 线上格式逐字段兼容：
 * 序列化结果为 {@code {code, msg, data}}；业务错误也是 HTTP 200 + code（见 baselines.md §1.1）。</p>
 *
 * <p>说明：微服务骨架阶段不直接依赖 {@code product-common}（其传递依赖含 MyBatis/Redis/POI 等，
 * 会拖垮空骨架的可启动性与隔离性）。当 product-common 按设计文档收敛为纯技术基础后，
 * 两套信封类合并，服务侧只保留本模块的定义（Phase 2+ 统一处理）。</p>
 */
public class AjaxResult extends HashMap<String, Object> {

    private static final long serialVersionUID = 1L;

    /** 状态码 */
    public static final String CODE_TAG = "code";

    /** 返回内容 */
    public static final String MSG_TAG = "msg";

    /** 数据对象 */
    public static final String DATA_TAG = "data";

    public AjaxResult() {
    }

    public AjaxResult(int code, String msg) {
        super.put(CODE_TAG, code);
        super.put(MSG_TAG, msg);
    }

    public AjaxResult(int code, String msg, Object data) {
        super.put(CODE_TAG, code);
        super.put(MSG_TAG, msg);
        if (Objects.nonNull(data)) {
            super.put(DATA_TAG, data);
        }
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

    public boolean isSuccess() {
        return Objects.equals(ApiStatus.SUCCESS, this.get(CODE_TAG));
    }

    public boolean isWarn() {
        return Objects.equals(ApiStatus.WARN, this.get(CODE_TAG));
    }

    public boolean isError() {
        return Objects.equals(ApiStatus.ERROR, this.get(CODE_TAG));
    }

    @Override
    public AjaxResult put(String key, Object value) {
        super.put(key, value);
        return this;
    }
}
