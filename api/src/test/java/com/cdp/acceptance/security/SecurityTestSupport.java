package com.cdp.acceptance.security;

import org.springframework.amqp.core.Declarables;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/** 認證驗收共用：Testcontainers，並以空拓樸取代 BE-02 尚未實作的 effectTopology。 */
@TestConfiguration(proxyBeanMethods = false)
public class SecurityTestSupport {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer("postgres:18-alpine");
    }

    @Bean
    @ServiceConnection
    RabbitMQContainer rabbit() {
        return new RabbitMQContainer("rabbitmq:4.3-management-alpine");
    }

    @Bean
    static BeanDefinitionRegistryPostProcessor emptyTopology() {
        return new BeanDefinitionRegistryPostProcessor() {
            @Override
            public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
                if (registry.containsBeanDefinition("effectTopology")) {
                    registry.removeBeanDefinition("effectTopology");
                    registry.registerBeanDefinition(
                            "effectTopology",
                            BeanDefinitionBuilder.genericBeanDefinition(
                                            Declarables.class, () -> new Declarables())
                                    .getBeanDefinition());
                }
            }

            @Override
            public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {}
        };
    }
}
