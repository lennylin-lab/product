package com.product.cloud.messaging.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 跨服务领域事件信封 v1（ADR-0004 §2，契约冻结）。
 *
 * <p>固定字段：</p>
 * <ul>
 *   <li>{@code eventId}：UUID，全局唯一，幂等键（消费方去重主键）；</li>
 *   <li>{@code eventType}：{@code {domain}.{entity}.{action}}（如 task.status.changed），
 *       同时是 RabbitMQ routing key（ADR-0004 §5）；</li>
 *   <li>{@code version}：envelope 契约版本，起始 1。演进策略：只加不改——新增可选
 *       payload 字段不升版本；字段语义/类型变更或删除必须升 {@code version} 并新增
 *       eventType（或 version 分支消费），禁止原地改写 v1 语义；</li>
 *   <li>{@code occurredAt}：ISO-8601（含时区偏移），事件发生时间（生产侧时钟）。
 *       消费方按"同聚合单调性"用它做乱序/延迟防护：早于该聚合已应用时间的过期事件
 *       记录后丢弃（不回滚已提交状态，ADR-0004 §4）；</li>
 *   <li>{@code producer}：生产者服务名（如 product-execution）；</li>
 *   <li>{@code aggregateId}：聚合根 ID（taskId / batchId / orderLineId，字符串形态）；</li>
 *   <li>{@code correlationId}：透传自触发命令/上游事件的 traceId，全链路串联；</li>
 *   <li>{@code payload}：各事件自有字段（见 {@link EventTypes} 的 schema 注释），
 *       只包含生产域已提交数据。</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class EventEnvelope {

    /** envelope 契约版本（v1 起步；见类注释演进策略）。 */
    public static final int CONTRACT_VERSION = 1;

    private String eventId;
    private String eventType;
    private int version = CONTRACT_VERSION;
    private String occurredAt;
    private String producer;
    private String aggregateId;
    private String correlationId;
    private Map<String, Object> payload = new LinkedHashMap<>();
}
