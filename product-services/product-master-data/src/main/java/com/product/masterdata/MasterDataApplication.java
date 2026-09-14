package com.product.masterdata;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * product-master-data 启动骨架（Phase 1，空应用，不含业务代码）。
 *
 * <p>ADR-0002：产品、工艺路线、工序规则、资源、机台、模具、能力、日历、换型规则；
 * 拥有 product、product_mold_param、product_route、route_operation、resource、machine、mold、
 * machine_mold_compatibility、resource_capability、calendar、changeover_rule 表
 * （ADR-0005 master_data_db，Phase 3 迁移）。</p>
 */
@SpringBootApplication
public class MasterDataApplication {

    public static void main(String[] args) {
        SpringApplication.run(MasterDataApplication.class, args);
    }
}
