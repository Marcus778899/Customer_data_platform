package com.cdp.acceptance.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import com.cdp.security.AuthProperties;
import com.cdp.security.LoginRequest;

import tools.jackson.databind.json.JsonMapper;

/** FIX-04 驗收：機密不經 toString 外洩（日誌、例外訊息、除錯輸出）。 */
class SecretToStringAcceptanceTest {

    // user-auth-api 改寫：AuthProperties／LoginRequest 的建構子簽章會隨實作改變（客服帳密移除、username → email），
    // 改以 Binder／Jackson 由屬性名與 JSON 欄位建立，測試意圖（toString 不外洩機密）不變。
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static AuthProperties bind(Map<String, Object> props) {
        return new Binder(new MapConfigurationPropertySource(props))
                .bind("cdp.security", AuthProperties.class)
                .orElseThrow(() -> new AssertionError("cannot bind AuthProperties"));
    }

    @Test
    void authPropertiesToStringHasNoSecrets() {
        AuthProperties p = bind(Map.of(
                "cdp.security.ingest-api-key", "ingest-SECRET-1a2b",
                "cdp.security.admin-api-key", "admin-SECRET-3c4d"));
        assertThat(p.toString()).doesNotContain("ingest-SECRET-1a2b", "admin-SECRET-3c4d");
    }

    @Test
    void authPropertiesToStringDoesNotNpeOnNulls() {
        assertThat(bind(Map.of("cdp.security.ingest-api-key", "x")).toString()).isNotNull();
    }

    @Test
    void loginRequestToStringHasNoPassword() throws Exception {
        LoginRequest r = JSON.readValue("{\"email\":\"a@example.com\",\"password\":\"pw-SECRET-7g8h\"}", LoginRequest.class);
        assertThat(r.toString()).doesNotContain("pw-SECRET-7g8h");
    }

    @Test
    void loginRequestToStringDoesNotNpeOnNulls() throws Exception {
        assertThat(JSON.readValue("{}", LoginRequest.class).toString()).isNotNull();
    }
}
