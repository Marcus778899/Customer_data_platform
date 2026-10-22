package com.cdp.acceptance.relayloop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import com.cdp.CdpApplication;
import com.cdp.acceptance.security.SecurityTestSupport;

/**
 * 啟動驗證：OUTBOX_RELAY_LEASE（cdp.relay.lease）必須大於 OUTBOX_RELAY_CONFIRM_TIMEOUT
 * （cdp.relay.confirm-timeout），否則拒絕啟動；排程執行緒池（spring.task.scheduling.pool.size）不得小於迴圈數。
 * 合法設定可啟動。以命令列參數覆蓋 application-test.yml。
 */
class RelayStartupValidationAcceptanceTest {

    private static String[] args(String... overrides) {
        Map<String, String> merged = new LinkedHashMap<>();
        for (String a :
                new String[] {
                    "--spring.rabbitmq.listener.simple.auto-startup=false",
                    "--cdp.engine.poll-interval=PT1H",
                    "--cdp.relay.poll-interval=PT1H",
                    "--cdp.relay.confirm-timeout=PT5S",
                    "--cdp.relay.lease=PT30S",
                    "--spring.task.scheduling.pool.size=4",
                    "--cdp.security.ingest-api-key=SECRET-aaaa-1111",
                    "--cdp.security.admin-api-key=SECRET-bbbb-2222"
                }) {
            merged.put(a.substring(0, a.indexOf('=')), a);
        }
        for (String a : overrides) {
            merged.put(a.substring(0, a.indexOf('=')), a);
        }
        return merged.values().toArray(String[]::new);
    }

    private static SpringApplicationBuilder app() {
        return new SpringApplicationBuilder(CdpApplication.class, SecurityTestSupport.class)
                .profiles("test");
    }

    private void assertStartFails(String mustMention, String... overrides) {
        String[] args = args(overrides);
        ConfigurableApplicationContext[] started = new ConfigurableApplicationContext[1];
        try {
            assertThatThrownBy(() -> started[0] = app().run(args))
                    .satisfies(
                            t -> {
                                StringBuilder all = new StringBuilder();
                                for (Throwable c = t; c != null; c = c.getCause()) {
                                    all.append(c).append("\n").append(c.getMessage()).append("\n");
                                }
                                assertThat(all.toString().toLowerCase())
                                        .as("cause 鏈訊息需點出設定：%s", all)
                                        .contains(mustMention);
                            });
        } finally {
            if (started[0] != null) {
                started[0].close();
            }
        }
    }

    @Test
    void leaseEqualToConfirmTimeout_failsStartup() {
        assertStartFails("lease", "--cdp.relay.confirm-timeout=PT7S", "--cdp.relay.lease=PT7S");
    }

    @Test
    void leaseShorterThanConfirmTimeout_failsStartup() {
        assertStartFails("lease", "--cdp.relay.confirm-timeout=PT20S", "--cdp.relay.lease=PT3S");
    }

    @Test
    void leaseLongerThanConfirmTimeout_starts() {
        try (ConfigurableApplicationContext ctx =
                app().run(args("--cdp.relay.confirm-timeout=PT5S", "--cdp.relay.lease=PT6S"))) {
            assertThat(ctx.isRunning()).isTrue();
        }
    }

    @Test
    void schedulerPoolSmallerThanLoopCount_failsStartup() {
        // 目前三個迴圈（EventDispatcher、AdvanceLoop、OutboxRelay）；池 = 1 不足
        assertStartFails("pool", "--spring.task.scheduling.pool.size=1");
    }

    @Test
    void schedulerPoolAtLeastLoopCount_starts() {
        try (ConfigurableApplicationContext ctx =
                app().run(args("--spring.task.scheduling.pool.size=4"))) {
            assertThat(ctx.isRunning()).isTrue();
        }
    }
}
