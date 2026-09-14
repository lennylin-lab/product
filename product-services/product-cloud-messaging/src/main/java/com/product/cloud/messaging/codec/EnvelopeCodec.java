package com.product.cloud.messaging.codec;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.product.cloud.messaging.api.EventEnvelope;
import com.product.cloud.messaging.config.MessagingCodecException;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * envelope JSON 编解码（生产与消费共用一个私有 ObjectMapper——不注册为 Spring Bean，
 * 避免影响使用服务自身的 Jackson 定制）。
 *
 * <p>序列化契约：字段名/envelope 类注释为准；未知字段忽略（向前兼容，ADR-0004 演进策略）。</p>
 */
public final class EnvelopeCodec {

    /** 事件时间生成格式：ISO-8601 含时区偏移（同机所有服务同一 TZ，偏移一致）。 */
    public static final DateTimeFormatter OCCURRED_AT_FORMAT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private EnvelopeCodec() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /** envelope → JSON 字节（AMQP body）。 */
    public static byte[] encode(EventEnvelope envelope) {
        try {
            return MAPPER.writeValueAsBytes(envelope);
        } catch (JsonProcessingException e) {
            throw new MessagingCodecException("事件信封序列化失败: " + envelope.getEventType(), e);
        }
    }

    /** JSON 字节 → envelope；结构非法抛 {@link MessagingCodecException}（消费方视为不可处理毒消息）。 */
    public static EventEnvelope parse(byte[] body) {
        try {
            return MAPPER.readValue(body, EventEnvelope.class);
        } catch (Exception e) {
            throw new MessagingCodecException("事件信封反序列化失败", e);
        }
    }

    /** 当前时刻的 occurredAt（生产侧生成）。 */
    public static String now() {
        return OffsetDateTime.now().format(OCCURRED_AT_FORMAT);
    }

    /** 解析 occurredAt；非法值抛 {@link MessagingCodecException}。 */
    public static OffsetDateTime parseOccurredAt(String occurredAt) {
        if (occurredAt == null || occurredAt.isBlank()) {
            throw new MessagingCodecException("事件信封缺少 occurredAt");
        }
        try {
            return OffsetDateTime.parse(occurredAt, OCCURRED_AT_FORMAT);
        } catch (DateTimeParseException lenient) {
            try {
                return OffsetDateTime.parse(occurredAt);
            } catch (DateTimeParseException fatal) {
                throw new MessagingCodecException("事件信封 occurredAt 非法: " + occurredAt, fatal);
            }
        }
    }
}
