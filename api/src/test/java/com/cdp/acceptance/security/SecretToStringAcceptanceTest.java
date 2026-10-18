package com.cdp.acceptance.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.cdp.security.AuthProperties;
import com.cdp.security.LoginRequest;

/** FIX-04 驗收：機密不經 toString 外洩（日誌、例外訊息、除錯輸出）。 */
class SecretToStringAcceptanceTest {

    @Test
    void authPropertiesToStringHasNoSecrets() {
        AuthProperties p = new AuthProperties("ingest-SECRET-1a2b", "admin-SECRET-3c4d", "agent-name", "pw-SECRET-5e6f");
        assertThat(p.toString()).doesNotContain("ingest-SECRET-1a2b", "admin-SECRET-3c4d", "pw-SECRET-5e6f");
    }

    @Test
    void authPropertiesToStringDoesNotNpeOnNulls() {
        assertThat(new AuthProperties(null, null, null, null).toString()).isNotNull();
    }

    @Test
    void loginRequestToStringHasNoPassword() {
        assertThat(new LoginRequest("agent-name", "pw-SECRET-7g8h").toString()).doesNotContain("pw-SECRET-7g8h");
    }

    @Test
    void loginRequestToStringDoesNotNpeOnNulls() {
        assertThat(new LoginRequest(null, null).toString()).isNotNull();
    }
}
