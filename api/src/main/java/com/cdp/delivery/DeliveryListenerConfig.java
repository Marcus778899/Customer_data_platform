package com.cdp.delivery;

import org.springframework.amqp.rabbit.config.ContainerCustomizer;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** listener container 關機等待（DELIVERY_SHUTDOWN_TIMEOUT）；Boot 沒有對應的 spring.rabbitmq 屬性。 */
@Configuration(proxyBeanMethods = false)
public class DeliveryListenerConfig {

    @Bean
    ContainerCustomizer<SimpleMessageListenerContainer> deliveryShutdownCustomizer(
            DeliveryProperties properties) {
        return container -> container.setShutdownTimeout(properties.shutdownTimeout().toMillis());
    }
}
