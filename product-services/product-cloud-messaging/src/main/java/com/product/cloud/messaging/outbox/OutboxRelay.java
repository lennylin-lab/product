package com.product.cloud.messaging.outbox;

import com.product.cloud.messaging.api.EventEnvelope;
import com.product.cloud.messaging.codec.EnvelopeCodec;
import com.product.cloud.messaging.config.MessagingProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 发件箱后台发布器（ADR-0004 §3）。
 *
 * <p>轮询 PENDING 行 → 投递 RabbitMQ（routing key = eventType，exchange = 本服务生产者
 * exchange）→ 等待 publisher confirm → 确认成功才标 PUBLISHED。失败按有界退避重试
 * （retry_count/next_retry_at），超限转 HALTED（人工重放，不无界重试）。</p>
 *
 * <p>顺序语义：单行失败不阻塞后续行投递（避免队头阻塞）；同聚合事件乱序由消费方的
 * occurredAt 单调性守卫兜底（过期事件记录后丢弃，ADR-0004 §4）——重试/重放产生的迟到
 * 事件因此天然幂等无害。</p>
 */
@Slf4j
public class OutboxRelay {

    private final OutboxDao outboxDao;
    private final RabbitTemplate rabbitTemplate;
    private final MessagingProperties properties;

    public OutboxRelay(OutboxDao outboxDao, RabbitTemplate rabbitTemplate, MessagingProperties properties) {
        this.outboxDao = outboxDao;
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
    }

    /**
     * 单轮投递。独立于业务事务（投递本身不写业务表，仅更新 outbox 行状态；
     * 用 REQUIRES_NEW 语义由调度线程直接调用——不参与任何业务事务）。
     */
    public void pollAndPublish() {
        MessagingProperties.Relay relay = properties.getRelay();
        List<OutboxMessage> pending = outboxDao.selectPending(relay.getBatchSize());
        for (OutboxMessage message : pending) {
            publishOne(message, relay);
        }
    }

    /** 调度入口（由使用服务的 @EnableScheduling 驱动；fixedDelay 防重入）。 */
    @Scheduled(fixedDelayString = "${product.messaging.relay.poll-interval-ms:1000}")
    public void scheduledPoll() {
        try {
            pollAndPublish();
        } catch (Exception e) {
            log.warn("outbox relay 轮询异常（下轮重试）: {}", e.getMessage());
        }
    }

    private void publishOne(OutboxMessage message, MessagingProperties.Relay relay) {
        try {
            EventEnvelope envelope = toEnvelope(message);
            org.springframework.amqp.rabbit.connection.CorrelationData correlation =
                    new org.springframework.amqp.rabbit.connection.CorrelationData(
                            message.getEventId() + ":" + UUID.randomUUID());
            // 直接构造 AMQP Message（原始 JSON 字节）——不经 RabbitTemplate 的 Jackson 转换器：
            // Jackson2JsonMessageConverter 会把 byte[] 序列化成 Base64 字符串（消费端反序列化失败，
            // 2026-09-15 live 实测缺陷），envelope 契约要求 body 即 JSON 对象字节。
            org.springframework.amqp.core.MessageProperties props =
                    new org.springframework.amqp.core.MessageProperties();
            props.setContentType("application/json");
            props.setContentEncoding("UTF-8");
            props.setDeliveryMode(org.springframework.amqp.core.MessageDeliveryMode.PERSISTENT);
            props.setHeader("eventId", message.getEventId());
            props.setHeader("eventType", message.getEventType());
            rabbitTemplate.send(properties.getExchange(), message.getEventType(),
                    new org.springframework.amqp.core.Message(EnvelopeCodec.encode(envelope), props),
                    correlation);
            org.springframework.amqp.rabbit.connection.CorrelationData.Confirm confirm =
                    correlation.getFuture().get(relay.getConfirmTimeoutMs(), TimeUnit.MILLISECONDS);
            if (confirm != null && confirm.isAck()) {
                outboxDao.markPublished(message.getId());
                if (message.getRetryCount() != null && message.getRetryCount() > 0) {
                    log.info("outbox 投递成功（重试 {} 次）: type={} eventId={}",
                            message.getRetryCount(), message.getEventType(), message.getEventId());
                }
            } else {
                String reason = confirm == null ? "confirm timeout" : confirm.getReason();
                log.warn("outbox 投递 nack: type={} eventId={} reason={}",
                        message.getEventType(), message.getEventId(), reason);
                outboxDao.markFailed(message, relay.getMaxAttempts(), relay.getBackoffInitialMs(),
                        relay.getBackoffMultiplier(), relay.getBackoffMaxMs());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            outboxDao.markFailed(message, relay.getMaxAttempts(), relay.getBackoffInitialMs(),
                    relay.getBackoffMultiplier(), relay.getBackoffMaxMs());
        } catch (Exception e) {
            log.warn("outbox 投递失败: type={} eventId={} err={} (retry {})",
                    message.getEventType(), message.getEventId(), e.getMessage(),
                    (message.getRetryCount() == null ? 0 : message.getRetryCount()) + 1);
            outboxDao.markFailed(message, relay.getMaxAttempts(), relay.getBackoffInitialMs(),
                    relay.getBackoffMultiplier(), relay.getBackoffMaxMs());
        }
    }

    /** outbox 行 → 完整信封（payload 由 JSON 还原；信封字段以行为准）。 */
    public static EventEnvelope toEnvelope(OutboxMessage message) {
        EventEnvelope envelope = new EventEnvelope();
        envelope.setEventId(message.getEventId());
        envelope.setEventType(message.getEventType());
        envelope.setVersion(EventEnvelope.CONTRACT_VERSION);
        java.time.LocalDateTime occurred = message.getOccurredAt() != null
                ? message.getOccurredAt()
                : java.time.LocalDateTime.now();
        envelope.setOccurredAt(occurred.atOffset(java.time.OffsetDateTime.now().getOffset())
                .format(EnvelopeCodec.OCCURRED_AT_FORMAT));
        envelope.setProducer(message.getProducer());
        envelope.setAggregateId(message.getAggregateId());
        envelope.setCorrelationId(message.getCorrelationId());
        envelope.setPayload(parsePayload(message.getPayload()));
        return envelope;
    }

    private static java.util.Map<String, Object> parsePayload(String payload) {
        if (payload == null || payload.isBlank()) {
            return new java.util.LinkedHashMap<>();
        }
        try {
            return EnvelopeCodec.mapper().readValue(payload,
                    new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {
                    });
        } catch (Exception e) {
            throw new com.product.cloud.messaging.config.MessagingCodecException(
                    "outbox payload 反序列化失败: " + abbreviate(payload), e);
        }
    }

    private static String abbreviate(String payload) {
        return payload.length() > 64 ? payload.substring(0, 64) + "..." : payload;
    }
}
