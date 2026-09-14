package com.product.cloud.common.api;

/**
 * 响应状态码常量（微服务体系错误契约）。
 *
 * <p>取值与现有单体 {@code com.product.common.constant.HttpStatus} 保持一致，
 * 保证统一切换后 {@code {code, msg, data}} 信封语义不变（baselines.md §1.3）。</p>
 */
public final class ApiStatus {

    /** 操作成功 */
    public static final int SUCCESS = 200;

    /** 未授权 */
    public static final int UNAUTHORIZED = 401;

    /** 访问受限，授权过期 */
    public static final int FORBIDDEN = 403;

    /** 资源，服务未找到 */
    public static final int NOT_FOUND = 404;

    /** 系统内部错误 */
    public static final int ERROR = 500;

    /** 系统警告消息 */
    public static final int WARN = 601;

    private ApiStatus() {
    }
}
