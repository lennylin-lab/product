package com.product.cloud.messaging.amqp;

import com.product.cloud.messaging.config.MessagingProperties;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;

import java.util.ArrayList;
import java.util.List;

/**
 * RabbitMQ 拓扑声明（ADR-0004 §5 命名规范，冻结）：
 *
 * <ul>
 *   <li>exchange：{@code {domain}.events}（direct，durable）；routing key = eventType；</li>
 *   <li>queue：{@code {consumer-service}.{event-domain}}（durable）；</li>
 *   <li>死信：每 queue 配 x-dead-letter-exchange={queue}.dlx / x-dead-letter-routing-key={queue}.dlq，
 *       DLQ 名 {queue}.dlq 绑定 {queue}.dlx（key 同名）；</li>
 *   <li>声明由消费者侧配置驱动（本类），生产者/消费者双侧幂等声明（参数必须一致）。</li>
 * </ul>
 */
public final class MessagingTopology {

    private MessagingTopology() {
    }

    public static Declarables build(MessagingProperties properties) {
        List<Declarable> declarables = new ArrayList<>();
        if (properties.isDeclareProducerExchange() && notBlank(properties.getExchange())) {
            declarables.add(new DirectExchange(properties.getExchange(), true, false));
        }
        for (MessagingProperties.QueueSpec spec : properties.getQueues()) {
            if (!notBlank(spec.getQueue()) || !notBlank(spec.getExchange())) {
                continue;
            }
            // 消费绑定他方 exchange：幂等声明（与生产者侧参数一致）
            declarables.add(new DirectExchange(spec.getExchange(), true, false));
            Queue queue = QueueBuilder.durable(spec.getQueue())
                    .deadLetterExchange(spec.getQueue() + ".dlx")
                    .deadLetterRoutingKey(spec.getQueue() + ".dlq")
                    .build();
            declarables.add(queue);
            for (String routingKey : spec.getRoutingKeys()) {
                declarables.add(BindingBuilder.bind(queue)
                        .to(new DirectExchange(spec.getExchange(), true, false))
                        .with(routingKey));
            }
            // 死信拓扑
            declarables.add(new DirectExchange(spec.getQueue() + ".dlx", true, false));
            Queue dlq = QueueBuilder.durable(spec.getQueue() + ".dlq").build();
            declarables.add(dlq);
            declarables.add(new Binding(spec.getQueue() + ".dlq", Binding.DestinationType.QUEUE,
                    spec.getQueue() + ".dlx", spec.getQueue() + ".dlq", null));
        }
        return new Declarables(declarables);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
