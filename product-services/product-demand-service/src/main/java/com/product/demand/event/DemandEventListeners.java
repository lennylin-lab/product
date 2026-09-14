package com.product.demand.event;

import com.product.cloud.messaging.api.EventEnvelope;
import com.product.cloud.messaging.deadletter.DeadLetterAuditor;
import com.product.demand.common.exception.ServiceException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * demand 事件消费入口（Phase 5，ADR-0004 §4）。
 *
 * <p>队列 {@code product-demand.planning}（绑定 planning.events 的
 * batch.progress.changed）；容器逐条处理成功后确认；业务失败/非法信封经有界重试
 * （默认 3 次退避）后拒绝进 DLX → 死信审计落库。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "product.messaging", name = "enabled", havingValue = "true")
public class DemandEventListeners {

    private final DemandEventConsumerService consumerService;
    private final DeadLetterAuditor deadLetterAuditor;

    public DemandEventListeners(DemandEventConsumerService consumerService,
                                DeadLetterAuditor deadLetterAuditor) {
        this.consumerService = consumerService;
        this.deadLetterAuditor = deadLetterAuditor;
    }

    /** 计划域批次进度事件（驱动订单行/订单状态推进）。 */
    @RabbitListener(queues = "product-demand.planning",
            containerFactory = "productListenerContainerFactory")
    public void onBatchProgressChanged(EventEnvelope envelope) {
        if (!com.product.cloud.messaging.api.EventTypes.BATCH_PROGRESS_CHANGED
                .equals(envelope.getEventType())) {
            // 未知/非法事件类型：不可处理毒消息 → 有界重试 → DLX → 审计
            throw new ServiceException("未知事件类型，无法消费: " + envelope.getEventType());
        }
        String outcome = consumerService.consumeBatchProgressChanged(envelope);
        log.debug("batch.progress.changed 处理完成: batchId={} outcome={} eventId={}",
                envelope.getAggregateId(), outcome, envelope.getEventId());
    }

    /**
     * 死信审计：消费重试耗尽的消息经 DLX 进入本队列，落库后由容器确认
     * （审计未落库不 ack——失败重回队列，死信不丢）。
     */
    @RabbitListener(queues = "product-demand.planning.dlq",
            containerFactory = "productDlqListenerContainerFactory")
    public void onDeadLetter(Message message) {
        deadLetterAuditor.audit(DemandEventConsumerService.CONSUMER_GROUP, message);
    }
}
