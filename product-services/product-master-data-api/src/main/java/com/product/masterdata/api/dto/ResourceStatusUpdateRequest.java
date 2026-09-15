package com.product.masterdata.api.dto;

import java.io.Serializable;

/**
 * 资源状态更新请求（KD3 资源权威状态回写契约，2026-09-16 异常事件建模增量）。
 *
 * <p>跨服务 DTO：无持久化注解（跨域契约规则）。消费方为 product-planning
 * （resource.status.changed 消费回写）。</p>
 */
public class ResourceStatusUpdateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 资源 ID（master_data_db resource.resource_id，必填） */
    private Long resourceId;

    /** 目标状态（AVAILABLE/BUSY/DOWN/MAINTENANCE/OFFSHIFT，必填，提供方校验） */
    private String toStatus;

    /** 原因编码（可选，追溯用；来自 resource.status.changed payload 的 reasonCode） */
    private String reasonCode;

    public Long getResourceId() {
        return resourceId;
    }

    public void setResourceId(Long resourceId) {
        this.resourceId = resourceId;
    }

    public String getToStatus() {
        return toStatus;
    }

    public void setToStatus(String toStatus) {
        this.toStatus = toStatus;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public void setReasonCode(String reasonCode) {
        this.reasonCode = reasonCode;
    }
}
