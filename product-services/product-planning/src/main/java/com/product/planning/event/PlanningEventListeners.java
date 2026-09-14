package com.product.planning.event;

import com.product.cloud.messaging.api.EventEnvelope;
import com.product.cloud.messaging.deadletter.DeadLetterAuditor;
import com.product.planning.common.exception.ServiceException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * planning 事件消费入口（Phase 5，ADR-0004 §4）。
 *
 * <p>队列 {@code product-planning.execution}（绑定 execution.events 的
 * task.status.changed / resource.status.changed）；单队列单监听器按 eventType 分发。
 * 容器逐条处理成功后确认；业务失败/非法信封经有界重试（默认 3 次退避）后拒绝进
 * DLX → 死信审计落库。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "product.messaging", name = "enabled", havingValue = "true")
public class PlanningEventListeners {

    private final PlanningEventConsumerService consumerService;
    private final DeadLetterAuditor deadLetterAuditor;

    public PlanningEventListeners(PlanningEventConsumerService consumerService,
                                  DeadLetterAuditor deadLetterAuditor) {
        this.consumerService = consumerService;
        this.deadLetterAuditor = deadLetterAuditor;
    }

    /** 执行域事件统一入口（按 eventType 分发；单队列单消费者，保持同队列处理顺序）。 */
    @RabbitListener(queues = "product-planning.execution",
            containerFactory = "productListenerContainerFactory")
    public void onExecutionEvent(EventEnvelope envelope) {
        String eventType = envelope.getEventType();
        if (com.product.cloud.messaging.api.EventTypes.TASK_STATUS_CHANGED.equals(eventType)) {
            String outcome = consumerService.consumeTaskStatusChanged(envelope);
            log.debug("task.status.changed 处理完成: taskId={} outcome={} eventId={}",
                    envelope.getAggregateId(), outcome, envelope.getEventId());
            return;
        }
        if (com.product.cloud.messaging.api.EventTypes.RESOURCE_STATUS_CHANGED.equals(eventType)) {
            consumerService.consumeResourceStatusChanged(envelope);
            return;
        }
        // 未知/非法事件类型：不可处理毒消息 → 有界重试 → DLX → 审计（不留在队列内重投）
        throw new ServiceException("未知事件类型，无法消费: " + eventType);
    }

    /**
     * 死信审计：消费重试耗尽的消息经 DLX 进入本队列，落库后由容器确认
     * （审计未落库不 ack——失败重回队列，死信不丢）。
     */
    @RabbitListener(queues = "product-planning.execution.dlq",
            containerFactory = "productDlqListenerContainerFactory")
    public void onDeadLetter(Message message) {
        deadLetterAuditor.audit(PlanningEventConsumerService.CONSUMER_GROUP, message);
    }
}
