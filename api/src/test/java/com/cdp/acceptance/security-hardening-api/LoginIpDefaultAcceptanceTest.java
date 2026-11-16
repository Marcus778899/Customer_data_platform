package com.cdp.acceptance.securityhardeningapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;

/**
 * security-hardening-api 驗收（DESIGN-05 自行決定表）：CDP_LOGIN_IP_MAX_FAILURES 未設定時預設為 30。
 * 只以 application.yml 解析（不含任何環境變數），並核對 .env.example 的範例值一致。
 */
class LoginIpDefaultAcceptanceTest {

    @Test
    void ip_max_failures_defaults_to_30_when_env_var_is_unset() throws Exception {
        List<PropertySource<?>> sources =
                new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        MutablePropertySources mps = new MutablePropertySources();
        sources.forEach(mps::addLast);
        PropertySourcesPropertyResolver resolver = new PropertySourcesPropertyResolver(mps);
        // 不設 CDP_LOGIN_IP_MAX_FAILURES：沒有預設值時這裡會因無法解析佔位符而丟例外
        assertThat(resolver.getProperty("cdp.login.ip-max-failures")).isEqualTo("30");
    }

    @Test
    void env_example_uses_the_same_default() throws Exception {
        List<String> lines = Files.readAllLines(Path.of("..", ".env.example"));
        assertThat(lines).contains("CDP_LOGIN_IP_MAX_FAILURES=30");
    }
}
