package com.cdp.acceptance.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import com.cdp.CdpApplication;

/**
 * FIX-04 驗收：金鑰或客服密碼為空、或兩把金鑰相同 → 啟動失敗，訊息不含機密值。
 * 以完整應用程式啟動（Testcontainers 提供 DB 與 MQ），確保失敗原因來自設定驗證而非基礎設施。
 * 假設：訊息（含 cause 鏈）會點出是哪個設定有問題，故要求出現 cdp.security／環境變數名／金鑰、密碼字樣之一。
 */
class StartupValidationAcceptanceTest {

    private static final String SECRET_A = "SECRET-aaaa-1111";
    private static final String SECRET_B = "SECRET-bbbb-2222";
    private static final String SECRET_PW = "SECRET-pppp-3333";
    private static final String SAME = "same-SECRET-key";

    private void assertStartFails(String... overrides) {
        List<String> props = new ArrayList<>(List.of(
                "spring.rabbitmq.listener.simple.auto-startup=false",
                "cdp.security.ingest-api-key=" + SECRET_A,
                "cdp.security.admin-api-key=" + SECRET_B,
                "cdp.security.support-username=agent",
                "cdp.security.support-password=" + SECRET_PW));
        props.addAll(List.of(overrides)); // 後者覆蓋前者
        ConfigurableApplicationContext[] started = new ConfigurableApplicationContext[1];
        try {
            assertThatThrownBy(() -> started[0] = new SpringApplicationBuilder(
                                    CdpApplication.class, SecurityTestSupport.class)
                            .profiles("test")
                            .properties(props.toArray(String[]::new))
                            .run())
                    .satisfies(t -> {
                        StringBuilder all = new StringBuilder();
                        for (Throwable c = t; c != null; c = c.getCause()) {
                            all.append(c).append('\n').append(c.getMessage()).append('\n');
                        }
                        String text = all.toString();
                        assertThat(text).doesNotContain(SECRET_A, SECRET_B, SECRET_PW, SAME);
                        assertThat(text).matches("(?is).*(cdp\\.security|CDP_[A-Z_]+|api-?key|support|金鑰|密碼).*");
                    });
        } finally {
            if (started[0] != null) started[0].close();
        }
    }

    @Test
    void emptyIngestKey_failsStartup() {
        assertStartFails("cdp.security.ingest-api-key=");
    }

    @Test
    void blankAdminKey_failsStartup() {
        assertStartFails("cdp.security.admin-api-key=   ");
    }

    @Test
    void emptySupportPassword_failsStartup() {
        assertStartFails("cdp.security.support-password=");
    }

    @Test
    void emptySupportUsername_failsStartup() {
        assertStartFails("cdp.security.support-username=");
    }

    @Test
    void identicalKeys_failStartup() {
        assertStartFails("cdp.security.ingest-api-key=" + SAME, "cdp.security.admin-api-key=" + SAME);
    }
}
