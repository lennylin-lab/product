package com.product.planning.domain.model;

import lombok.Data;

import java.util.List;

/**
 * 产品排程视图（Phase 4）：与单体 Product/ProductMoldParam 消费面同构的纯内存模型
 * （product/product_mold_param 归 product-master-data，经 getProducts 契约加载映射）。
 */
@Data
public class Product {

    private Long productId;

    private String productName;

    /** 材料编码（换型比较输入）。 */
    private String materialCode;

    /** 颜色编码（换型比较输入）。 */
    private String colorCode;

    /** 产品模具参数（A2 产能模型输入）。 */
    private List<ProductMoldParam> moldParams;

    /** 启用路线的工序定义（sequence 升序 → opCode 升序，与单体读取排序一致；无启用路线时空表）。 */
    private List<RouteOperation> activeOperations;
}
