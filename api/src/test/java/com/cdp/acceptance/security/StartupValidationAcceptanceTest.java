package com.cdp.acceptance.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import com.cdp.CdpApplication;

/**
 * FIX-04 驗收（user-auth-api 改寫：客服帳密 CDP_SUPPORT_* 已廢除，移除對應啟動參數與兩個空值測試）：金鑰為空、或兩把金鑰相同 → 啟動失敗，訊息不含機密值。
 * 以完整應用程式啟動（Testcontainers 提供 DB 與 MQ），確保失敗原因來自設定驗證而非基礎設施。
 * cause 鏈須含 IllegalStateException，且訊息點出 cdp.security. 開頭的屬性名。
 */
class StartupValidationAcceptanceTest {

    private static final String SECRET_A = "SECRET-aaaa-1111";
    private static final String SECRET_B = "SECRET-bbbb-2222";
    private static final String SECRET_PW = "SECRET-pppp-3333";
    private static final String SAME = "same-SECRET-key";

    private void assertStartFails(String... overrides) {
        // 重複的命令列選項會被合併成逗號串，故以 Map 去重，後者覆蓋前者
        Map<String, String> merged = new LinkedHashMap<>();
        for (String a : List.of(
                "--spring.rabbitmq.listener.simple.auto-startup=false",
                "--cdp.security.ingest-api-key=" + SECRET_A,
                "--cdp.security.admin-api-key=" + SECRET_B)) {
            merged.put(a.substring(0, a.indexOf('=')), a);
        }
        for (String a : overrides) {
            merged.put(a.substring(0, a.indexOf('=')), a);
        }
        String[] args = merged.values().toArray(String[]::new);
        ConfigurableApplicationContext[] started = new ConfigurableApplicationContext[1];
        try {
            assertThatThrownBy(() -> started[0] = new SpringApplicationBuilder(
                                    CdpApplication.class, SecurityTestSupport.class)
                            .profiles("test")
                            .run(args))
                    .satisfies(t -> {
                        StringBuilder all = new StringBuilder();
                        boolean found = false;
                        for (Throwable c = t; c != null; c = c.getCause()) {
                            all.append(c).append("\n").append(c.getMessage()).append("\n");
                            if (c instanceof IllegalStateException
                                    && c.getMessage() != null
                                    && c.getMessage().contains("cdp.security.")) {
                                found = true;
                            }
                        }
                        String text = all.toString();
                        assertThat(text).doesNotContain(SECRET_A, SECRET_B, SECRET_PW, SAME);
                        assertThat(found)
                                .as("cause 鏈需含 IllegalStateException 且訊息含 cdp.security. 屬性名：%s", text)
                                .isTrue();
                    });
        } finally {
            if (started[0] != null) started[0].close();
        }
    }

    @Test
    void emptyIngestKey_failsStartup() {
        assertStartFails("--cdp.security.ingest-api-key=");
    }

    @Test
    void blankAdminKey_failsStartup() {
        assertStartFails("--cdp.security.admin-api-key=   ");
    }

    @Test
    void identicalKeys_failStartup() {
        assertStartFails("--cdp.security.ingest-api-key=" + SAME, "--cdp.security.admin-api-key=" + SAME);
    }
}
