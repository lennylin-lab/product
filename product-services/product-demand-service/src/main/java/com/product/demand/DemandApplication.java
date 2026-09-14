package com.product.demand;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * product-demand 启动骨架（Phase 1，空应用，不含业务代码）。
 *
 * <p>ADR-0002：客户、订单、订单行及订单生命周期；
 * 拥有 customer/customer_order/order_line 表（Phase 3 迁移）。</p>
 *
 * <p>所在 Maven 模块为 product-demand-service（坐标与旧业务模块 product-demand 冲突，
 * 故加 -service 后缀）；Nacos 注册名仍是 product-demand。</p>
 */
@SpringBootApplication
public class DemandApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemandApplication.class, args);
    }
}
