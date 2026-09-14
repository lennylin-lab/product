package com.product.cloud.messaging.outbox;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 发件箱行（ADR-0004 §3：业务行与 outbox 行同事务写入）。
 *
 * <p>表 {@code event_outbox} 归属各生产者服务自有库（ADR-0005：每服务库内同名权属表）。
 * status 生命周期：PENDING →（relay 投递 + publisher confirm）→ PUBLISHED；
 * 投递失败按退避留在 PENDING（retry_count/next_retry_at 递增），超过有界次数转
 * HALTED（人工经管理端点重放）。replayed_from 记录人工重放来源 eventId（重放=
 * 新 eventId + 原 correlationId + 原 payload，ADR-0004 §6）。</p>
 */
@Data
public class OutboxMessage {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_HALTED = "HALTED";

    private Long id;
    private String eventId;
    private String eventType;
    private String aggregateId;
    private String correlationId;
    private String producer;
    /** envelope.payload 的 JSON 串。 */
    private String payload;
    private String status = STATUS_PENDING;
    private Integer retryCount = 0;
    private LocalDateTime nextRetryAt;
    /** 人工重放来源 eventId（首次发布为 null）。 */
    private String replayedFrom;
    private LocalDateTime occurredAt;
    private LocalDateTime createdAt;
    private LocalDateTime publishedAt;
}
