package com.product.verify.core;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 4. Spring AMQP: declare durable queue, publish, consume, and observe the round trip. */
@Configuration
@RestController
class AmqpVerifyConfig {

    static final String EXCHANGE = "verify.exchange";
    static final String QUEUE = "verify.queue";
    static final String ROUTING_KEY = "verify.routing.key";

    static final List<String> RECEIVED = new CopyOnWriteArrayList<>();

    @Bean
    Queue verifyQueue() {
        return new Queue(QUEUE, true);
    }

    @Bean
    DirectExchange verifyExchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    @Bean
    Binding verifyBinding() {
        return BindingBuilder.bind(verifyQueue()).to(verifyExchange()).with(ROUTING_KEY);
    }

    @RabbitListener(queues = QUEUE)
    public void onMessage(String message) {
        RECEIVED.add(message);
    }

    @PostMapping("/amqp/send")
    public String send(@RequestParam String text) {
        rabbitTemplate().convertAndSend(EXCHANGE, ROUTING_KEY, text);
        return "sent:" + text;
    }

    @GetMapping("/amqp/received")
    public List<String> received() {
        return RECEIVED;
    }

    private final RabbitTemplate rabbitTemplate;

    AmqpVerifyConfig(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    RabbitTemplate rabbitTemplate() {
        return rabbitTemplate;
    }
}
