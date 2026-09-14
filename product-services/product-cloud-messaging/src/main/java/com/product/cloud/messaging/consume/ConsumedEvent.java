package com.product.cloud.messaging.consume;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 消费流水行（ADR-0004 §4：以 eventId 为去重键记录消费流水）。
 * 表 {@code consumed_event} 归属各消费方服务自有库。
 * outcome：APPLIED（已应用）/ STALE（过期事件丢弃）/ UNKNOWN（聚合不存在等无操作场景）。
 */
@Data
public class ConsumedEvent {

    public static final String OUTCOME_APPLIED = "APPLIED";
    public static final String OUTCOME_STALE = "STALE";
    public static final String OUTCOME_UNKNOWN = "UNKNOWN";

    private Long id;
    private String consumerGroup;
    private String eventId;
    private String eventType;
    private String aggregateId;
    private LocalDateTime occurredAt;
    private String outcome;
    private String payload;
    private LocalDateTime consumedAt;
}
