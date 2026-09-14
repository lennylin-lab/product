package com.product.cloud.security.jwt;

/** JWT 验签失败（签名不匹配 / 过期 / 格式非法 / alg 不支持）。 */
public class JwtVerificationException extends Exception {

    private static final long serialVersionUID = 1L;

    public JwtVerificationException(String message) {
        super(message);
    }

    public JwtVerificationException(String message, Throwable cause) {
        super(message, cause);
    }
}
