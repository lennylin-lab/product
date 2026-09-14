package com.product.cloud.messaging.config;

import com.product.cloud.messaging.amqp.MessagingTopology;
import com.product.cloud.messaging.audit.OpsAuditService;
import com.product.cloud.messaging.consume.ConsumedEventRecorder;
import com.product.cloud.messaging.deadletter.DeadLetterAuditor;
import com.product.cloud.messaging.outbox.OutboxDao;
import com.product.cloud.messaging.outbox.OutboxPublisher;
import com.product.cloud.messaging.outbox.OutboxRelay;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.Jackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.DefaultJackson2JavaTypeMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.retry.interceptor.RetryOperationsInterceptor;
import org.springframework.util.StringUtils;

/**
 * 事件一致性基础设施自动装配（ADR-0004；product.messaging.enabled=true 时生效）。
 *
 * <p>依赖使用服务提供：{@link JdbcTemplate}（与 MyBatis 同数据源/同事务管理器——
 * "业务写 + outbox/去重写"原子性的根基）、RabbitMQ ConnectionFactory
 * （spring.rabbitmq.*，publisher-confirm-type 需设为 correlated）与
 * {@code @EnableScheduling}（驱动 OutboxRelay 轮询）。</p>
 *
 * <p>ack 语义说明（ADR-0004 §4"手动 ack"的等价实现）：业务消费容器采用
 * AcknowledgeMode.AUTO + 处理成功后才由容器按条确认——异常路径经有界重试拦截器，
 * 耗尽后 RejectAndDontRequeueRecoverer 拒绝不重回队列 → DLX；与"监听器内手动
 * basicAck"在逐条处理成功后才确认这一保证上等价，且避免手动 ack 与重试拦截器
 * 互相打架。死信审计容器（DLQ）使用无重试、失败重回队列的独立容器工厂，
 * 审计未落库前消息不丢。</p>
 */
@Slf4j
@AutoConfiguration(after = RabbitAutoConfiguration.class)
@ConditionalOnProperty(prefix = "product.messaging", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(MessagingProperties.class)
public class MessagingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public OutboxDao outboxDao(JdbcTemplate jdbcTemplate) {
        return new OutboxDao(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public OutboxPublisher outboxPublisher(OutboxDao outboxDao, MessagingProperties properties) {
        return new OutboxPublisher(outboxDao, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public ConsumedEventRecorder consumedEventRecorder(JdbcTemplate jdbcTemplate) {
        return new ConsumedEventRecorder(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public DeadLetterAuditor deadLetterAuditor(JdbcTemplate jdbcTemplate) {
        return new DeadLetterAuditor(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public OpsAuditService opsAuditService(JdbcTemplate jdbcTemplate) {
        return new OpsAuditService(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public com.product.cloud.messaging.replay.EventReplayService eventReplayService(
            OutboxDao outboxDao, DeadLetterAuditor deadLetterAuditor, OpsAuditService opsAuditService,
            RabbitTemplate productEventRabbitTemplate, MessagingProperties properties) {
        return new com.product.cloud.messaging.replay.EventReplayService(
                outboxDao, deadLetterAuditor, opsAuditService, productEventRabbitTemplate, properties);
    }

    /** 拓扑声明（exchange/queue/binding/DLX/DLQ，ADR-0004 §5）。 */
    @Bean
    public org.springframework.amqp.core.Declarables productMessagingTopology(MessagingProperties properties) {
        return MessagingTopology.build(properties);
    }

    /**
     * 事件消费容器工厂：有界重试（退避）→ 耗尽进 DLX；按条成功后确认；
     * INFERRED 类型优先（信封统一反序列化为 EventEnvelope，不信任对端 TypeId）。
     */
    @Bean(name = "productListenerContainerFactory")
    public SimpleRabbitListenerContainerFactory productListenerContainerFactory(
            ConnectionFactory connectionFactory, MessagingProperties properties) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setConcurrentConsumers(1);
        factory.setPrefetchCount(10);
        factory.setAcknowledgeMode(org.springframework.amqp.core.AcknowledgeMode.AUTO);
        factory.setDefaultRequeueRejected(false);
        factory.setAdviceChain(consumerRetryInterceptor(properties));
        factory.setMessageConverter(eventMessageConverter());
        return factory;
    }

    /** 死信审计容器工厂：无重试、失败重回 DLQ（审计未落库不丢消息）。 */
    @Bean(name = "productDlqListenerContainerFactory")
    public SimpleRabbitListenerContainerFactory productDlqListenerContainerFactory(
            ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setConcurrentConsumers(1);
        factory.setPrefetchCount(10);
        factory.setAcknowledgeMode(org.springframework.amqp.core.AcknowledgeMode.AUTO);
        factory.setDefaultRequeueRejected(true);
        return factory;
    }

    @Bean
    public RetryOperationsInterceptor productConsumerRetryInterceptor(MessagingProperties properties) {
        return consumerRetryInterceptor(properties);
    }

    private static RetryOperationsInterceptor consumerRetryInterceptor(MessagingProperties properties) {
        return RetryInterceptorBuilder.stateless()
                .maxAttempts(properties.getConsumer().getMaxAttempts())
                .backOffOptions(properties.getConsumer().getInitialIntervalMs(),
                        properties.getConsumer().getMultiplier(),
                        properties.getConsumer().getMaxIntervalMs())
                .recoverer(new RejectAndDontRequeueRecoverer())
                .build();
    }

    private static Jackson2JsonMessageConverter eventMessageConverter() {
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter();
        DefaultJackson2JavaTypeMapper typeMapper = new DefaultJackson2JavaTypeMapper();
        typeMapper.setTypePrecedence(Jackson2JavaTypeMapper.TypePrecedence.INFERRED);
        converter.setJavaTypeMapper(typeMapper);
        return converter;
    }

    /** 事件专用 RabbitTemplate（publisher confirm 由 spring.rabbitmq.publisher-confirm-type=correlated 打开）。 */
    @Bean
    @ConditionalOnMissingBean(name = "productEventRabbitTemplate")
    public RabbitTemplate productEventRabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(new Jackson2JsonMessageConverter());
        return template;
    }

    @Bean
    @ConditionalOnProperty(prefix = "product.messaging.relay", name = "enabled", havingValue = "true", matchIfMissing = true)
    public OutboxRelay outboxRelay(OutboxDao outboxDao, RabbitTemplate productEventRabbitTemplate,
                                   MessagingProperties properties) {
        if (!StringUtils.hasText(properties.getExchange()) || !StringUtils.hasText(properties.getProducer())) {
            log.warn("product.messaging 已启用但 producer/exchange 未配置，outbox relay 不会投递");
        }
        return new OutboxRelay(outboxDao, productEventRabbitTemplate, properties);
    }
}
