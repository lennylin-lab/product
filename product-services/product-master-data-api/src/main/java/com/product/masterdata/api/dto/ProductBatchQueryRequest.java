package com.product.masterdata.api.dto;

import java.io.Serializable;
import java.util.List;

/**
 * 产品批量查询请求（Phase 3 契约；ADR-0002 决策 5：demand/planning 以业务 ID 调用
 * Master Data 批量查询，禁止跨服务读写他方表）。
 *
 * <p>{@code productIds} 为空或 null 时返回全部产品（Phase 4 排程快照全量加载用）；
 * 非空时仅返回给定 ID 中存在的产品。单次调用上限 {@value #MAX_IDS} 条，
 * 超限由提供方拒绝（防止契约被当成分页遍历的 N+1 通道）。</p>
 */
public class ProductBatchQueryRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final int MAX_IDS = 1000;

    private List<Long> productIds;

    public ProductBatchQueryRequest() {
    }

    public ProductBatchQueryRequest(List<Long> productIds) {
        this.productIds = productIds;
    }

    public List<Long> getProductIds() {
        return productIds;
    }

    public void setProductIds(List<Long> productIds) {
        this.productIds = productIds;
    }
}
