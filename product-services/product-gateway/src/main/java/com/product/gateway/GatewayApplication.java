package com.product.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * product-gateway 启动骨架（Phase 1，空应用，不含业务代码）。
 *
 * <p>ADR-0002：唯一外部入口，负责路由、CORS、限流、JWT 前置校验、trace 注入。
 * 路由/权限前置/统一错误响应的业务配置在 Phase 2 落地；当前路由仅为骨架冒烟用。</p>
 */
@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
