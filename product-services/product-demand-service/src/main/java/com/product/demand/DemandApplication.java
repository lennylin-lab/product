package com.product.demand;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * product-demand 需求域服务（Phase 3 迁移）。
 *
 * <p>ADR-0002：客户、订单、订单行及订单生命周期；
 * 拥有 customer/customer_order/order_line 表（ADR-0005 demand_db，Phase 3 迁移）。</p>
 *
 * <p>所在 Maven 模块为 product-demand-service（坐标与旧业务模块 product-demand 冲突，
 * 故加 -service 后缀）；Nacos 注册名仍是 product-demand。</p>
 *
 * <p>Feign 契约扫描范围仅限 {@code com.product.masterdata.api}（主数据批量查询契约）：
 * 订单行 product_id 跨域引用校验的调用通道，超时/重试语义见 application.yml
 * （fail-closed：master-data 不可用时拒绝写入，不做本地降级放行）。</p>
 */
@SpringBootApplication
@EnableFeignClients(basePackages = "com.product.masterdata.api")
public class DemandApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemandApplication.class, args);
    }
}
