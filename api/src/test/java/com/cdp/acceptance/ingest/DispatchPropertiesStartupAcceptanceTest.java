package com.cdp.acceptance.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import com.cdp.CdpApplication;
import com.cdp.acceptance.security.SecurityTestSupport;

/**
 * FIX-05 驗收：事件派發重試設定不合法 → 啟動失敗，cause 鏈訊息點出 cdp.engine.dispatch. 設定名且不含所給的值；
 * D-027 預設值（5、PT10S、PT10M）可啟動。以命令列參數覆蓋 application-test.yml。
 */
class DispatchPropertiesStartupAcceptanceTest {

    private static final String P = "--cdp.engine.dispatch.";

    private static String[] args(String... overrides) {
        // 重複的命令列選項會被合併成逗號串，故以 Map 去重，後者覆蓋前者
        Map<String, String> merged = new LinkedHashMap<>();
        for (String a : List.of(
                "--spring.rabbitmq.listener.simple.auto-startup=false",
                "--cdp.engine.poll-interval=PT1H",
                "--cdp.relay.poll-interval=PT1H",
                "--cdp.security.ingest-api-key=SECRET-aaaa-1111",
                "--cdp.security.admin-api-key=SECRET-bbbb-2222",
                "--cdp.security.support-username=agent",
                "--cdp.security.support-password=SECRET-pppp-3333",
                P + "max-attempts=5",
                P + "backoff-initial=PT10S",
                P + "backoff-max=PT10M")) {
            merged.put(a.substring(0, a.indexOf('=')), a);
        }
        for (String a : overrides) {
            merged.put(a.substring(0, a.indexOf('=')), a);
        }
        return merged.values().toArray(String[]::new);
    }

    private static SpringApplicationBuilder app() {
        return new SpringApplicationBuilder(CdpApplication.class, SecurityTestSupport.class).profiles("test");
    }

    private void assertStartFails(List<String> leakedValues, String... overrides) {
        String[] args = args(overrides);
        ConfigurableApplicationContext[] started = new ConfigurableApplicationContext[1];
        try {
            assertThatThrownBy(() -> started[0] = app().run(args)).satisfies(t -> {
                StringBuilder all = new StringBuilder();
                for (Throwable c = t; c != null; c = c.getCause()) {
                    all.append(c).append("\n").append(c.getMessage()).append("\n");
                }
                String text = all.toString();
                assertThat(text)
                        .as("cause 鏈訊息需含 cdp.engine.dispatch. 設定名：%s", text)
                        .contains("cdp.engine.dispatch.");
                for (String v : leakedValues) {
                    assertThat(text).as("訊息不可含值 %s", v).doesNotContain(v);
                }
            });
        } finally {
            if (started[0] != null) started[0].close();
        }
    }

    @Test
    void maxAttemptsZero_failsStartup() {
        assertStartFails(List.of(), P + "max-attempts=0");
    }

    @Test
    void maxAttemptsNegative_failsStartup() {
        assertStartFails(List.of("-37"), P + "max-attempts=-37");
    }

    @Test
    void backoffInitialZero_isAllowed() {
        // 追加 1：0 代表下一輪輪詢即重試，允許；只拒絕負值
        try (ConfigurableApplicationContext ctx = app().run(args(P + "backoff-initial=PT0S"))) {
            assertThat(ctx.isRunning()).isTrue();
        }
    }

    @Test
    void backoffInitialNegative_failsStartup() {
        assertStartFails(List.of("PT-7S", "-PT7S"), P + "backoff-initial=-PT7S");
    }

    @Test
    void backoffMaxLessThanInitial_failsStartup() {
        assertStartFails(
                List.of("PT47S", "PT13S"), P + "backoff-initial=PT47S", P + "backoff-max=PT13S");
    }

    @Test
    void d027Defaults_startSuccessfully() {
        try (ConfigurableApplicationContext ctx = app().run(args())) {
            assertThat(ctx.isRunning()).isTrue();
        }
    }
}
