package com.product.masterdata.api.dto;

import java.io.Serializable;
import java.util.List;

/**
 * 资源批量查询请求（Phase 4 排程加载机台/模具/能力矩阵用）。
 *
 * <p>{@code resourceIds} 为 null 时返回全部资源；非空时仅返回给定 ID 中存在的资源。
 * {@code resourceTypes} 为 null/空时不过滤类型；单次 ID 上限 {@value #MAX_IDS}。</p>
 */
public class ResourceBatchQueryRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final int MAX_IDS = 1000;

    private List<Long> resourceIds;

    private List<String> resourceTypes;

    public ResourceBatchQueryRequest() {
    }

    public ResourceBatchQueryRequest(List<Long> resourceIds) {
        this.resourceIds = resourceIds;
    }

    public List<Long> getResourceIds() {
        return resourceIds;
    }

    public void setResourceIds(List<Long> resourceIds) {
        this.resourceIds = resourceIds;
    }

    public List<String> getResourceTypes() {
        return resourceTypes;
    }

    public void setResourceTypes(List<String> resourceTypes) {
        this.resourceTypes = resourceTypes;
    }
}
