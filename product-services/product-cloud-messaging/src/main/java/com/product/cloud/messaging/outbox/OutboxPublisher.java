package com.product.cloud.messaging.outbox;

import com.product.cloud.messaging.api.EventEnvelope;
import com.product.cloud.messaging.codec.EnvelopeCodec;
import com.product.cloud.messaging.config.MessagingProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

/**
 * 事务内发件箱写入器（ADR-0004 §3）。
 *
 * <p>用法：在业务写事务方法内调用 {@link #append(EventEnvelope)}——outbox 行与业务行
 * 同一本地 ACID 事务提交/回滚。禁止"先发消息后提交事务"或"事务内直接 await 发送"
 * （投递由 {@link OutboxRelay} 后台完成）。</p>
 */
@Slf4j
public class OutboxPublisher {

    private final OutboxDao outboxDao;
    private final MessagingProperties properties;

    public OutboxPublisher(OutboxDao outboxDao, MessagingProperties properties) {
        this.outboxDao = outboxDao;
        this.properties = properties;
    }

    /**
     * 将信封写入发件箱（与调用方业务写同事务）。
     * envelope 的 producer/version 缺省时由本服务配置补齐。
     */
    @Transactional
    public void append(EventEnvelope envelope) {
        if (envelope == null || envelope.getEventType() == null || envelope.getEventType().isBlank()) {
            throw new IllegalArgumentException("事件信封缺少 eventType");
        }
        if (envelope.getEventId() == null || envelope.getEventId().isBlank()) {
            envelope.setEventId(java.util.UUID.randomUUID().toString());
        }
        if (envelope.getProducer() == null || envelope.getProducer().isBlank()) {
            envelope.setProducer(properties.getProducer());
        }
        envelope.setVersion(EventEnvelope.CONTRACT_VERSION);
        if (envelope.getOccurredAt() == null || envelope.getOccurredAt().isBlank()) {
            envelope.setOccurredAt(EnvelopeCodec.now());
        }
        OutboxMessage message = new OutboxMessage();
        message.setEventId(envelope.getEventId());
        message.setEventType(envelope.getEventType());
        message.setAggregateId(envelope.getAggregateId());
        message.setCorrelationId(envelope.getCorrelationId());
        message.setProducer(envelope.getProducer());
        message.setPayload(writePayload(envelope));
        message.setOccurredAt(EnvelopeCodec.parseOccurredAt(envelope.getOccurredAt()).toLocalDateTime());
        outboxDao.insert(message);
        log.debug("outbox appended: type={} aggregate={} eventId={}",
                envelope.getEventType(), envelope.getAggregateId(), envelope.getEventId());
    }

    private String writePayload(EventEnvelope envelope) {
        try {
            return EnvelopeCodec.mapper().writeValueAsString(
                    envelope.getPayload() == null ? java.util.Map.of() : envelope.getPayload());
        } catch (Exception e) {
            throw new com.product.cloud.messaging.config.MessagingCodecException(
                    "outbox payload 序列化失败: " + envelope.getEventType(), e);
        }
    }
}
