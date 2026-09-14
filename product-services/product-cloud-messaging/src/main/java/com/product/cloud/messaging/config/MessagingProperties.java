package com.product.cloud.messaging.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 事件基础设施配置（ADR-0004）。各服务在 application.yml 以 {@code product.messaging.*}
 * 打开（enabled=true 才装配 Rabbit/Outbox 组件——骨架与离线单测不受影响）。
 *
 * <p>拓扑命名规范（ADR-0004 §5，冻结）：</p>
 * <ul>
 *   <li>exchange：{@code {domain}.events}（direct，durable），domain ∈ execution/planning/demand；
 *       由生产者与消费者双侧声明（幂等，参数必须一致）；</li>
 *   <li>routing key = eventType；</li>
 *   <li>queue：{@code {consumer-service}.{event-domain}}（durable），如 product-planning.execution；</li>
 *   <li>死信：每个 queue 配 x-dead-letter-exchange={queue}.dlx、x-dead-letter-routing-key={queue}.dlq，
 *       DLQ 名 {@code {queue}.dlq} 绑定到 {@code {queue}.dlx}；</li>
 *   <li>queue/DLQ 参数变更视为契约破坏（需升 envelope version 并评审，ADR-0004 §5）。</li>
 * </ul>
 */
@Data
@ConfigurationProperties(prefix = "product.messaging")
public class MessagingProperties {

    /** 总开关：默认关闭（骨架/离线单测无 Rabbit 依赖）。 */
    private boolean enabled = false;

    /** envelope.producer 与本服务 outbox 归属（如 product-execution）。 */
    private String producer;

    /** 本服务生产者 exchange（如 execution.events）。 */
    private String exchange;

    /** 是否声明本服务生产者 exchange（消费者服务需声明他方 exchange 以完成绑定——幂等）。 */
    private boolean declareProducerExchange = true;

    private final Relay relay = new Relay();

    private final Consumer consumer = new Consumer();

    /** 本服务作为消费方要建立/绑定的队列（可多个）。 */
    private List<QueueSpec> queues = new ArrayList<>();

    /** 死信审计监听队列（本服务的 {queue}.dlq；空=不启动审计监听）。 */
    private String deadLetterQueue;

    /** 死信重放目标 exchange（= 原事件来源 exchange）。 */
    private String deadLetterReplayExchange;

    @Data
    public static class Relay {
        /** 后台发布器开关。 */
        private boolean enabled = true;
        /** 轮询间隔（毫秒）。 */
        private long pollIntervalMs = 1000;
        /** 单轮最大投递条数。 */
        private int batchSize = 100;
        /** publisher confirm 等待超时（毫秒）。 */
        private long confirmTimeoutMs = 5000;
        /** 有界重试：单条消息最大投递尝试次数（超过转 HALTED，人工处理，ADR-0004 §3）。 */
        private int maxAttempts = 8;
        /** 投递失败退避：初始间隔（毫秒）。 */
        private long backoffInitialMs = 1000;
        /** 投递失败退避：倍率。 */
        private double backoffMultiplier = 2.0;
        /** 投递失败退避：上限（毫秒）。 */
        private long backoffMaxMs = 300000;
    }

    @Data
    public static class Consumer {
        /** 消费失败有界重试次数（经 RetryInterceptor，耗尽后进 DLX，ADR-0004 §4）。 */
        private int maxAttempts = 3;
        /** 消费失败重试初始间隔（毫秒）。 */
        private long initialIntervalMs = 500;
        /** 消费失败重试倍率。 */
        private double multiplier = 2.0;
        /** 消费失败重试最大间隔（毫秒）。 */
        private long maxIntervalMs = 5000;
    }

    @Data
    public static class QueueSpec {
        /** 队列名（{consumer-service}.{event-domain}）。 */
        private String queue;
        /** 绑定的 exchange。 */
        private String exchange;
        /** 绑定的 routing key 集合（= eventType 集合）。 */
        private List<String> routingKeys = new ArrayList<>();
    }
}
