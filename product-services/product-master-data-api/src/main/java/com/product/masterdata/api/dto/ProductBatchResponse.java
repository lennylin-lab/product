package com.product.masterdata.api.dto;

import java.io.Serializable;
import java.util.List;

/**
 * 产品批量查询响应信封。
 *
 * <p>{@code snapshotVersion} 为 master_data_db 单调递增变更计数（任一主数据写事务内 +1），
 * 供 Phase 4 排程在运行前后对比检测输入漂移；products 仅含存在的产品行。</p>
 */
public class ProductBatchResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    private long snapshotVersion;

    private List<ProductDTO> products;

    public long getSnapshotVersion() {
        return snapshotVersion;
    }

    public void setSnapshotVersion(long snapshotVersion) {
        this.snapshotVersion = snapshotVersion;
    }

    public List<ProductDTO> getProducts() {
        return products;
    }

    public void setProducts(List<ProductDTO> products) {
        this.products = products;
    }
}
