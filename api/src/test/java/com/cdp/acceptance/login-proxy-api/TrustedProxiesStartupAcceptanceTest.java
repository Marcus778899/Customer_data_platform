package com.cdp.acceptance.loginproxyapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;

import com.cdp.CdpApplication;
import com.cdp.acceptance.security.SecurityTestSupport;

/**
 * login-proxy-api 驗收（auth.md §3「來源 IP」格式與啟動檢查，D-091）：CDP_TRUSTED_PROXIES（屬性
 * {@code cdp.security.trusted-proxies}）必填；不符格式 → 後端拒絕啟動，cause 鏈含 IllegalStateException，
 * 訊息含變數名稱與第幾項、不回顯值；合法值可啟動。
 */
class TrustedProxiesStartupAcceptanceTest {

    private static final String VAR = "CDP_TRUSTED_PROXIES";
    private static final String PROP = "cdp.security.trusted-proxies";

    private static ConfigurableApplicationContext start(String value) {
        return new SpringApplicationBuilder(CdpApplication.class, SecurityTestSupport.class)
                .profiles("test")
                .run(
                        "--spring.rabbitmq.listener.simple.auto-startup=false",
                        "--cdp.engine.poll-interval=PT1H",
                        "--cdp.relay.poll-interval=PT1H",
                        "--" + PROP + "=" + value);
    }

    /** 啟動必須失敗；訊息含變數名稱與 {@code itemNo}（0 = 不檢查），且不含 {@code leaked}。 */
    private static void assertRejected(String value, int itemNo, String... leaked) {
        ConfigurableApplicationContext[] started = new ConfigurableApplicationContext[1];
        try {
            assertThatThrownBy(() -> started[0] = start(value)).satisfies(t -> {
                StringBuilder all = new StringBuilder();
                String ise = null;
                for (Throwable c = t; c != null; c = c.getCause()) {
                    all.append(c).append('\n');
                    if (c instanceof IllegalStateException
                            && c.getMessage() != null
                            && c.getMessage().contains(VAR)) {
                        ise = c.getMessage();
                    }
                }
                assertThat(ise)
                        .as("cause 鏈需含 IllegalStateException 且訊息含 %s：%s", VAR, all)
                        .isNotNull();
                if (itemNo > 0) {
                    assertThat(ise).as("訊息需指出第幾項").contains(String.valueOf(itemNo));
                }
                for (String s : leaked) {
                    assertThat(all.toString()).as("訊息不可回顯設定值").doesNotContain(s);
                }
            });
        } finally {
            if (started[0] != null) started[0].close();
        }
    }

    private static void assertStarts(String value) {
        try (ConfigurableApplicationContext ctx = start(value)) {
            assertThat(ctx.isRunning()).isTrue();
        }
    }

    @Test
    void placeholder_value_is_rejected() {
        assertRejected("CHANGE_ME", 0);
        assertRejected("change_me", 0);
    }

    @Test
    void blank_value_is_rejected_as_unset() {
        assertRejected("", 0);
        assertRejected("   ", 0);
    }

    @Test
    void application_yml_gives_no_default_so_unset_cannot_resolve() throws Exception {
        List<PropertySource<?>> sources =
                new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        MutablePropertySources mps = new MutablePropertySources();
        sources.forEach(mps::addLast);
        PropertySourcesPropertyResolver resolver = new PropertySourcesPropertyResolver(mps);
        // 不設 CDP_TRUSTED_PROXIES：有預設值時這裡會解析成功（M-1：不可預設信任私有網段）
        assertThatThrownBy(() -> resolver.getProperty(PROP)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cidr_range_is_rejected_and_names_the_item() {
        assertRejected("127.0.0.1,10.0.0.0/8", 2, "10.0.0.0/8");
        assertRejected("10.0.0.0/8", 1, "10.0.0.0/8");
    }

    @Test
    void hostname_is_rejected_without_echoing_it() {
        assertRejected("127.0.0.1,10.0.0.1,::1,proxy.invalid-host.example", 4, "proxy.invalid-host.example");
    }

    @Test
    void dash_mixed_with_addresses_is_rejected() {
        assertRejected("-,127.0.0.1", 0);
        assertRejected("127.0.0.1,-", 2);
    }

    @Test
    void empty_item_is_rejected() {
        assertRejected("127.0.0.1,,::1", 2);
        assertRejected("127.0.0.1,", 2);
    }

    @Test
    void brackets_zone_id_and_wildcards_are_rejected() {
        assertRejected("[::1]", 1);
        assertRejected("fe80::1%eth0", 1, "eth0");
        assertRejected("0.0.0.0", 1);
        assertRejected("::", 1);
    }

    @Test
    void malformed_addresses_are_rejected() {
        assertRejected("256.1.1.1", 1);
        assertRejected("1.2.3", 1);
        assertRejected("1.2.3.4.5", 1);
        assertRejected("127.0.0.1,gggg::1", 2);
    }

    @Test
    void more_than_ten_items_are_rejected() {
        String eleven = IntStream.rangeClosed(1, 11).mapToObj(i -> "192.0.2." + i).collect(Collectors.joining(","));
        assertRejected(eleven, 0);
    }

    @Test
    void dash_starts_up() {
        assertStarts("-");
        assertStarts("  -  ");
    }

    @Test
    void exact_addresses_start_up() {
        assertStarts("127.0.0.1,::1");
        assertStarts(" 127.0.0.1 , ::1 ");
        assertStarts("2001:db8::1,::ffff:192.0.2.1");
    }

    @Test
    void ten_items_start_up() {
        String ten = IntStream.rangeClosed(1, 10).mapToObj(i -> "192.0.2." + i).collect(Collectors.joining(","));
        assertStarts(ten);
    }

    @Test
    void env_example_declares_the_placeholder_with_explanation() throws Exception {
        List<String> lines = Files.readAllLines(Path.of("..", ".env.example"));
        int idx = lines.indexOf("CDP_TRUSTED_PROXIES=CHANGE_ME");
        assertThat(idx).as(".env.example 需有 CDP_TRUSTED_PROXIES=CHANGE_ME").isGreaterThanOrEqualTo(0);
        StringBuilder comment = new StringBuilder();
        for (int i = idx - 1; i >= 0 && lines.get(i).startsWith("#"); i--) {
            comment.append(lines.get(i)).append('\n');
        }
        String c = comment.toString();
        assertThat(c).as("說明：只填確切位址").contains("確切");
        assertThat(c).as("說明：代理覆寫 X-Forwarded-For").contains("X-Forwarded-For").contains("覆寫");
        assertThat(Pattern.compile("(`-`|'-'|\"-\"|=\\s*-(\\s|$))").matcher(c).find())
                .as("說明：需交代 - 的語意")
                .isTrue();
    }
}
