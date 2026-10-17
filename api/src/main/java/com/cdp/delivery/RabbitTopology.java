package com.cdp.delivery;

import org.springframework.amqp.core.Declarables;
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

    @Bean
    public Declarables effectTopology() {
        throw new UnsupportedOperationException("TODO(BE-02)");
    }
}
