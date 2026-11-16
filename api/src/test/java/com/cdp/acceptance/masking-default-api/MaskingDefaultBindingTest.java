package com.cdp.acceptance.maskingdefaultapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.io.IOException;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import com.cdp.query.MaskingProperties;

/**
 * 未設定 CDP_TRACE_PLAIN_KEYS（變數不存在）= 內建預設 {name}；有設定（含空字串、-）以設定取代。
 * application-test.yml 固定了 plain-keys，HTTP 層無法測「未設定」，所以這裡只載入 main 的
 * application.yml，以真實的 Binder 與佔位符解析綁定 cdp.trace，驗證 yml 的預設寫法真的產生預設值
 * （例如 SpEL 的 null 寫法不會被 Binder 求值，會變成字面字串，這裡會抓到）。
 */
class MaskingDefaultBindingTest {

    private static MaskingProperties bind(Map<String, Object> env) throws IOException {
        StandardEnvironment e = new StandardEnvironment();
        e.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        e.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        if (!env.isEmpty()) {
            e.getPropertySources().addLast(new MapPropertySource("fakeEnv", env));
        }
        for (PropertySource<?> s :
                new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))) {
            e.getPropertySources().addLast(s);
        }
        Binder binder =
                new Binder(ConfigurationPropertySources.get(e), new PropertySourcesPlaceholdersResolver(e));
        return binder.bindOrCreate("cdp.trace", MaskingProperties.class);
    }

    @Test
    void variableAbsentUsesBuiltInDefaultName() throws Exception {
        assumeThat(System.getenv("CDP_TRACE_PLAIN_KEYS")).isNull();
        assertThat(bind(Map.of()).plainKeys()).containsExactlyInAnyOrder("name");
    }

    @Test
    void configuredValueReplacesDefault() throws Exception {
        assertThat(bind(Map.of("CDP_TRACE_PLAIN_KEYS", "email, tier")).plainKeys())
                .containsExactlyInAnyOrder("email", "tier");
    }

    @Test
    void dashMeansNoPlainKeys() throws Exception {
        assertThat(bind(Map.of("CDP_TRACE_PLAIN_KEYS", "-")).plainKeys()).isEmpty();
    }

    @Test
    void emptyStringMeansNoPlainKeys() throws Exception {
        assertThat(bind(Map.of("CDP_TRACE_PLAIN_KEYS", "")).plainKeys()).isEmpty();
    }
}
