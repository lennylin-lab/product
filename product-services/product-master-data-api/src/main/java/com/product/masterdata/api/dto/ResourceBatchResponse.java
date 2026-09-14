package com.product.masterdata.api.dto;

import java.io.Serializable;
import java.util.List;

/**
 * 资源批量查询响应信封。
 */
public class ResourceBatchResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    private long snapshotVersion;

    private List<ResourceDTO> resources;

    public long getSnapshotVersion() {
        return snapshotVersion;
    }

    public void setSnapshotVersion(long snapshotVersion) {
        this.snapshotVersion = snapshotVersion;
    }

    public List<ResourceDTO> getResources() {
        return resources;
    }

    public void setResources(List<ResourceDTO> resources) {
        this.resources = resources;
    }
}
