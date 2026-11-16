package com.cdp.acceptance.securityhardeningapi;

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
import com.cdp.acceptance.support.TestKeys;

/**
 * security-hardening-api 驗收（#152，auth.md §1 金鑰強度）：兩把金鑰去前後空白後須 >= 32 字、不含 CHANGE_ME（不分大小寫）；
 * 不符 → 啟動失敗，cause 鏈含 IllegalStateException、訊息點出是哪一把（cdp.security.ingest-api-key／admin-api-key），不含金鑰值。
 * 剛好 32 字可啟動。
 */
class KeyStrengthStartupAcceptanceTest {

    private static final String INGEST = "cdp.security.ingest-api-key";
    private static final String ADMIN = "cdp.security.admin-api-key";

    private static String pad(String prefix, int length) {
        return (prefix + "0".repeat(length)).substring(0, length);
    }

    private static String[] args(String ingest, String admin) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String a : List.of(
                "--spring.rabbitmq.listener.simple.auto-startup=false",
                "--cdp.engine.poll-interval=PT1H",
                "--cdp.relay.poll-interval=PT1H",
                "--" + INGEST + "=" + ingest,
                "--" + ADMIN + "=" + admin)) {
            m.put(a.substring(0, a.indexOf('=')), a);
        }
        return m.values().toArray(String[]::new);
    }

    private static ConfigurableApplicationContext start(String ingest, String admin) {
        return new SpringApplicationBuilder(CdpApplication.class, SecurityTestSupport.class)
                .profiles("test")
                .run(args(ingest, admin));
    }

    /** 啟動必須失敗；訊息指出 {@code variable}，且不含任何 {@code secrets}。 */
    private static void assertRejected(String variable, String ingest, String admin, String... secrets) {
        ConfigurableApplicationContext[] started = new ConfigurableApplicationContext[1];
        try {
            assertThatThrownBy(() -> started[0] = start(ingest, admin)).satisfies(t -> {
                StringBuilder all = new StringBuilder();
                boolean found = false;
                for (Throwable c = t; c != null; c = c.getCause()) {
                    all.append(c).append('\n');
                    if (c instanceof IllegalStateException
                            && c.getMessage() != null
                            && c.getMessage().contains(variable)) {
                        found = true;
                    }
                }
                for (String s : secrets) {
                    assertThat(all.toString()).as("訊息不可印出金鑰值").doesNotContain(s.strip());
                }
                assertThat(found)
                        .as("cause 鏈需含 IllegalStateException 且訊息含 %s：%s", variable, all)
                        .isTrue();
            });
        } finally {
            if (started[0] != null) started[0].close();
        }
    }

    @Test
    void ingestKeyOf31Chars_failsStartup() {
        String weak = pad("short-ingest-", 31);
        assertRejected(INGEST, weak, TestKeys.ADMIN, weak);
    }

    @Test
    void adminKeyOf31Chars_failsStartup() {
        String weak = pad("short-admin-", 31);
        assertRejected(ADMIN, TestKeys.INGEST, weak, weak);
    }

    @Test
    void keyIsTrimmedBeforeLengthCheck() {
        // 去前後空白後只有 20 字（含空白共 40 字）
        String weak = "  " + pad("trimmed-key-", 20) + " ".repeat(18);
        assertRejected(INGEST, weak, TestKeys.ADMIN, weak);
    }

    @Test
    void placeholderIngestKey_failsStartupEvenWhenLongEnough() {
        String placeholder = pad("CHANGE_ME", 40);
        assertRejected(INGEST, placeholder, TestKeys.ADMIN, placeholder);
    }

    @Test
    void placeholderAdminKey_isCaseInsensitive() {
        String lower = pad("change_me", 40);
        assertRejected(ADMIN, TestKeys.INGEST, lower, lower);
        String mixed = pad("Change_Me", 40);
        assertRejected(ADMIN, TestKeys.INGEST, mixed, mixed);
    }

    @Test
    void placeholderInsideAKey_failsStartup() {
        String embedded = "prod-" + "cHaNgE_mE" + "-" + "0".repeat(30);
        assertRejected(INGEST, embedded, TestKeys.ADMIN, embedded);
    }

    @Test
    void keysOfExactly32Chars_startUp() {
        try (ConfigurableApplicationContext ctx = start(pad("exact-ingest-", 32), pad("exact-admin--", 32))) {
            assertThat(ctx.isRunning()).isTrue();
        }
    }

    @Test
    void sharedTestKeysAreValid_startUp() {
        try (ConfigurableApplicationContext ctx = start(TestKeys.INGEST, TestKeys.ADMIN)) {
            assertThat(ctx.isRunning()).isTrue();
        }
    }
}
