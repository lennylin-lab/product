package com.product.cloud.messaging;

import com.product.cloud.messaging.config.MessagingProperties;
import com.product.cloud.messaging.outbox.OutboxDao;
import com.product.cloud.messaging.outbox.OutboxMessage;
import com.product.cloud.messaging.outbox.OutboxRelay;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OutboxRelay 裸 AMQP 线格式回归测试（Phase 5 live 缺陷固化，Phase 6 交接项）。
 *
 * <p>背景：2026-09-15 Phase 5 live 实测发现——经 RabbitTemplate 的 Jackson 转换器发
 * {@code byte[]} 会被 Base64 编码成字符串，消费端 EventEnvelope 反序列化必败（首个 task
 * 事件即重试耗尽进 DLX）。修复后 OutboxRelay 直接构造 AMQP Message（原始 JSON 字节）。
 * 本测试用 Mockito 捕获投递的 Message，把"裸 JSON 线格式"固化为离线回归：
 * content-type/编码/持久化/eventId·eventType 头/body 即 JSON 对象字节（无 Base64）。</p>
 */
class OutboxRelayWireFormatTest {

    private static final String EXCHANGE = "execution.events";

    private OutboxDao outboxDao;
    private RabbitTemplate rabbitTemplate;
    private OutboxRelay relay;

    @BeforeEach
    void setUp() {
        outboxDao = mock(OutboxDao.class);
        rabbitTemplate = mock(RabbitTemplate.class);
        MessagingProperties properties = new MessagingProperties();
        properties.setExchange(EXCHANGE);
        // 无 broker：mock send 不回调 confirm，等待即超时——仅影响投递后状态标记，不影响线格式断言
        properties.getRelay().setConfirmTimeoutMs(50);
        relay = new OutboxRelay(outboxDao, rabbitTemplate, properties);
    }

    private OutboxMessage pendingMessage() {
        OutboxMessage message = new OutboxMessage();
        message.setId(1L);
        message.setEventId("wire-format-event-1");
        message.setEventType("task.status.changed");
        message.setAggregateId("9731");
        message.setCorrelationId("trace-phase6");
        message.setProducer("product-execution");
        message.setPayload("{\"taskId\":9731,\"targetStatus\":\"RUNNING\"}");
        message.setOccurredAt(LocalDateTime.of(2026, 9, 15, 10, 0, 0));
        message.setStatus(OutboxMessage.STATUS_PENDING);
        return message;
    }

    @Test
    void publishShouldSendRawJsonMessageWithAmqpHeaders() throws Exception {
        when(outboxDao.selectPending(anyInt())).thenReturn(List.of(pendingMessage()));

        relay.pollAndPublish();

        ArgumentCaptor<String> exchange = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> routingKey = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Message> message = ArgumentCaptor.forClass(Message.class);
        ArgumentCaptor<org.springframework.amqp.rabbit.connection.CorrelationData> correlation =
                ArgumentCaptor.forClass(org.springframework.amqp.rabbit.connection.CorrelationData.class);
        verify(rabbitTemplate).send(exchange.capture(), routingKey.capture(), message.capture(),
                correlation.capture());

        assertEquals(EXCHANGE, exchange.getValue(), "必须投递到本服务生产者 exchange");
        assertEquals("task.status.changed", routingKey.getValue(), "routing key 必须 = eventType（ADR-0004 §5）");

        Message sent = message.getValue();
        assertNotNull(sent.getBody(), "消息体必须直接携带字节（非转换器包装）");

        // 线格式头：application/json + UTF-8 + 持久化 + eventId/eventType 头
        assertEquals("application/json", sent.getMessageProperties().getContentType(),
                "content-type 必须 application/json（消费端 EventEnvelope 转换契约）");
        assertEquals("UTF-8", sent.getMessageProperties().getContentEncoding());
        assertEquals(MessageDeliveryMode.PERSISTENT, sent.getMessageProperties().getDeliveryMode(),
                "事件消息必须持久化（ADR-0004 §3）");
        assertEquals("wire-format-event-1", sent.getMessageProperties().getHeaders().get("eventId"));
        assertEquals("task.status.changed", sent.getMessageProperties().getHeaders().get("eventType"));

        // 线格式体：body 即 JSON 对象字节——以 '{' 开头、可解析、字段与信封一致、绝无 Base64。
        // （历史缺陷形态：Jackson 转换器会把 byte[] 序列化成 Base64 字符串，body 形如
        //   "eyJldmVudElkIjoiLi4u" 且外层为字符串引号——以下断言对该形态全部失败。）
        String body = new String(sent.getBody(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(body.trim().startsWith("{"), "body 必须是裸 JSON 对象，实际: " + body);
        assertTrue(body.trim().endsWith("}"), "body 必须是裸 JSON 对象，实际: " + body);
        assertFalse(body.contains("eyJ"), "body 不得为 Base64 编码（Jackson byte[] 缺陷形态）");
        var json = com.product.cloud.messaging.codec.EnvelopeCodec.mapper().readValue(
                body, new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {
                });
        assertEquals("wire-format-event-1", json.get("eventId"));
        assertEquals("task.status.changed", json.get("eventType"));
        assertEquals(1, ((Number) json.get("version")).intValue());
        assertEquals("product-execution", json.get("producer"));
        assertEquals("9731", json.get("aggregateId"));
        assertEquals("trace-phase6", json.get("correlationId"));
        assertEquals(9731, ((Number) ((java.util.Map<?, ?>) json.get("payload")).get("taskId")).intValue());
        assertEquals("RUNNING", ((java.util.Map<?, ?>) json.get("payload")).get("targetStatus"));
    }

    @Test
    void publishShouldSendOneMessagePerPendingRowWithoutHeadBlocking() {
        OutboxMessage first = pendingMessage();
        OutboxMessage second = pendingMessage();
        second.setId(2L);
        second.setEventId("wire-format-event-2");
        second.setEventType("batch.progress.changed");
        second.setAggregateId("9721");
        when(outboxDao.selectPending(anyInt())).thenReturn(List.of(first, second));

        relay.pollAndPublish();

        ArgumentCaptor<Message> message = ArgumentCaptor.forClass(Message.class);
        ArgumentCaptor<org.springframework.amqp.rabbit.connection.CorrelationData> correlation =
                ArgumentCaptor.forClass(org.springframework.amqp.rabbit.connection.CorrelationData.class);
        verify(rabbitTemplate, org.mockito.Mockito.times(2))
                .send(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                        message.capture(), correlation.capture());
        assertEquals("task.status.changed", message.getAllValues().get(0).getMessageProperties().getHeaders().get("eventType"));
        assertEquals("batch.progress.changed", message.getAllValues().get(1).getMessageProperties().getHeaders().get("eventType"));
        for (Message sent : message.getAllValues()) {
            String body = new String(sent.getBody(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(body.trim().startsWith("{"), "每行都必须是裸 JSON 线格式: " + body);
        }
    }
}
