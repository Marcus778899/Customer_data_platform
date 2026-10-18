package com.cdp.delivery;

import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.ContainerCustomizer;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** C 類背景任務的 exchange／queue／DLQ（INIT-01「RabbitMQ」）。業務 queue 不設 TTL、不用延遲 plugin。 */
@Configuration
public class RabbitTopology {

    public static final String EXCHANGE = "cdp.effects";
    public static final String SEND_MESSAGE_ROUTING_KEY = "send-message";
    public static final String SEND_MESSAGE_QUEUE = "cdp.effects.send-message";
    public static final String DEAD_LETTER_EXCHANGE = "cdp.effects.dlx";
    public static final String SEND_MESSAGE_DLQ = "cdp.effects.send-message.dlq";

    /**
     * queue 暫時不存在時不讓整個 context 啟動失敗（例如 Spring 測試框架重啟快取的 context）；
     * 正式環境 {@link #effectTopology()} 會宣告 queue，行為不變。
     */
    @Bean
    public ContainerCustomizer<SimpleMessageListenerContainer> listenerContainerCustomizer() {
        return container -> container.setMissingQueuesFatal(false);
    }

    @Bean
    public Declarables effectTopology() {
        DirectExchange exchange = new DirectExchange(EXCHANGE, true, false);
        DirectExchange dlx = new DirectExchange(DEAD_LETTER_EXCHANGE, true, false);
        Queue queue =
                QueueBuilder.durable(SEND_MESSAGE_QUEUE)
                        .withArgument("x-dead-letter-exchange", DEAD_LETTER_EXCHANGE)
                        .withArgument("x-dead-letter-routing-key", SEND_MESSAGE_ROUTING_KEY)
                        .build();
        Queue dlq = QueueBuilder.durable(SEND_MESSAGE_DLQ).build();
        return new Declarables(
                exchange,
                dlx,
                queue,
                dlq,
                BindingBuilder.bind(queue).to(exchange).with(SEND_MESSAGE_ROUTING_KEY),
                BindingBuilder.bind(dlq).to(dlx).with(SEND_MESSAGE_ROUTING_KEY));
    }
}
