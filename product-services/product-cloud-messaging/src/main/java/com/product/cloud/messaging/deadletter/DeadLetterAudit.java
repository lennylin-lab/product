package com.product.cloud.messaging.deadletter;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 死信审计行（ADR-0004 §4：死信附原始 envelope 与失败原因落库审计）。
 *
 * <p>消费重试耗尽的消息经 DLX 进入本服务 DLQ，由死信审计监听器落库后 ack
 * （DLQ 保持空、以审计表为准）。人工重放（ADR-0004 §6）：从本表按 aggregateId/
 * eventType 取原始 envelope，以新 eventId + 原 correlationId 重投原 exchange，
 * 并标记 replayed。</p>
 */
@Data
public class DeadLetterAudit {

    private Long id;
    private String consumerGroup;
    /** 来源队列（进入 DLQ 前的 queue，取自 x-death）。 */
    private String sourceQueue;
    private String eventId;
    private String eventType;
    private String aggregateId;
    private String correlationId;
    private String payload;
    /** 死信原因（x-death reason + 最近异常摘要）。 */
    private String failureReason;
    private Boolean replayed = false;
    /** 重放时使用的新 eventId。 */
    private String replayEventId;
    private LocalDateTime createdAt;
    private LocalDateTime replayedAt;
}
