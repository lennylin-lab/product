package com.product.masterdata.api.dto;

import java.io.Serializable;
import java.util.List;

/**
 * 产品存在性校验响应（demand 订单行引用产品时的跨域校验，ADR-0002 决策 5）。
 */
public class ProductExistenceResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    private long snapshotVersion;

    /** 请求 productIds 中实际存在的 ID 子集（保持请求顺序，去重）。 */
    private List<Long> existingIds;

    public long getSnapshotVersion() {
        return snapshotVersion;
    }

    public void setSnapshotVersion(long snapshotVersion) {
        this.snapshotVersion = snapshotVersion;
    }

    public List<Long> getExistingIds() {
        return existingIds;
    }

    public void setExistingIds(List<Long> existingIds) {
        this.existingIds = existingIds;
    }
}
