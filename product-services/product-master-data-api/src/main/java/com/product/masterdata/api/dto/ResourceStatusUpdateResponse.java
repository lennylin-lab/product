package com.product.masterdata.api.dto;

import java.io.Serializable;

/**
 * 资源状态更新响应（KD3 资源权威状态回写契约，2026-09-16 异常事件建模增量）。
 *
 * <p>消费方 fail-closed 依据：提供方业务拒绝（资源不存在/目标状态非法）按错误契约返回
 * 200 + 错误体（不抛 HTTP 错误），经反序列化后 {@code resourceId} 为 null——消费方以
 * 「响应缺 resourceId 或 status 与请求 toStatus 不一致」判定回写未生效并抛错不 ack。</p>
 */
public class ResourceStatusUpdateResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 资源 ID（回写生效的资源；拒绝时缺省） */
    private Long resourceId;

    /** 回写后的资源状态（与请求 toStatus 一致；同状态幂等重写时为原状态） */
    private String status;

    /** 回写后的主数据版本计数（状态变化时已同事务 +1；同状态幂等重写时不变） */
    private long snapshotVersion;

    public Long getResourceId() {
        return resourceId;
    }

    public void setResourceId(Long resourceId) {
        this.resourceId = resourceId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public long getSnapshotVersion() {
        return snapshotVersion;
    }

    public void setSnapshotVersion(long snapshotVersion) {
        this.snapshotVersion = snapshotVersion;
    }
}
