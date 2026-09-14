package com.product.masterdata;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * product-master-data 主数据域服务（Phase 3 迁移）。
 *
 * <p>ADR-0002：产品、工艺路线、工序规则、资源、机台、模具、能力、日历、换型规则；
 * 拥有 product、product_mold_param、product_route、route_operation、resource、machine、mold、
 * machine_mold_compatibility、resource_capability、calendar、changeover_rule 表
 * （ADR-0005 master_data_db）。</p>
 *
 * <p>Feign 契约扫描范围（Phase 4 接线，RouteDeleteGuard 启用路线删除保护的跨域确认链）：</p>
 * <ul>
 *   <li>{@code com.product.demand.api}：按产品取订单行 ID（demand_batch_query 契约）；</li>
 *   <li>{@code com.product.planning.api}：订单行下阻塞工序任务判断（planning_batch 契约）。</li>
 * </ul>
 * <p>超时/重试/失败语义见 application.yml 与 RouteDeleteGuard（fail-closed：任一依赖不可达
 * 即拒绝删除启用中路线，绝不静默放行）。</p>
 */
@SpringBootApplication
@EnableFeignClients(basePackages = {"com.product.demand.api", "com.product.planning.api"})
public class MasterDataApplication {

    public static void main(String[] args) {
        SpringApplication.run(MasterDataApplication.class, args);
    }
}
