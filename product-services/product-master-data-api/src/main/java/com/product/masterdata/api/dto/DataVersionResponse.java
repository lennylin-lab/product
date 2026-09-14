package com.product.masterdata.api.dto;

import java.io.Serializable;

/**
 * 数据版本计数响应（Phase 4 新增，轻量端点）。
 *
 * <p>排程快照漂移检测在"加载前 / 加载后"两次调用本端点对比 {@code snapshotVersion}；
 * 计数语义见 baselines.md §2 补充记录（master_data_data_version 服务权属表）。</p>
 */
public class DataVersionResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    private long snapshotVersion;

    public DataVersionResponse() {
    }

    public DataVersionResponse(long snapshotVersion) {
        this.snapshotVersion = snapshotVersion;
    }

    public long getSnapshotVersion() {
        return snapshotVersion;
    }

    public void setSnapshotVersion(long snapshotVersion) {
        this.snapshotVersion = snapshotVersion;
    }
}
