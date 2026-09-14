package com.product.cloud.security.jwt;

import io.jsonwebtoken.Claims;

/**
 * 验签成功的 token 结果（header kid + claims）。
 */
public record VerifiedToken(String kid, Claims claims) {
}
