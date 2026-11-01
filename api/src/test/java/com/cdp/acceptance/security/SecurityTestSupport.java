package com.cdp.acceptance.security;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistrar;

import com.cdp.acceptance.support.SharedContainers;

/** 認證驗收共用：Testcontainers（使用真實 RabbitTopology）。 */
@TestConfiguration(proxyBeanMethods = false)
public class SecurityTestSupport {

    @Bean
    DynamicPropertyRegistrar sharedContainers(Environment env) {
        return SharedContainers.registrar(false);
    }
}
