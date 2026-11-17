package com.cdp.security;

import java.util.Locale;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * 啟動時驗證 cdp.security.*（auth.md §1 金鑰強度）：金鑰去前後空白後須 >= 32 字、不得含 CHANGE_ME（不分大小寫）、
 * 兩把不得相同。訊息只講設定名稱，不含值。
 */
@Component
class AuthPropertiesValidator implements InitializingBean {

    static final int MIN_KEY_LENGTH = 32;
    private static final String PLACEHOLDER = "change_me";

    private final AuthProperties properties;

    AuthPropertiesValidator(AuthProperties properties) {
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        require(properties.ingestApiKey(), "cdp.security.ingest-api-key（CDP_INGEST_API_KEY）");
        require(properties.adminApiKey(), "cdp.security.admin-api-key（CDP_ADMIN_API_KEY）");
        if (properties.ingestApiKey().strip().equals(properties.adminApiKey().strip())) {
            throw new IllegalStateException(
                    "cdp.security.ingest-api-key 與 cdp.security.admin-api-key 不可相同（兩把金鑰必須不同）");
        }
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("設定 " + name + " 不可為空");
        }
        String trimmed = value.strip();
        if (trimmed.toLowerCase(Locale.ROOT).contains(PLACEHOLDER)) {
            throw new IllegalStateException("設定 " + name + " 仍是佔位值（含 CHANGE_ME），請換成隨機金鑰");
        }
        if (trimmed.length() < MIN_KEY_LENGTH) {
            throw new IllegalStateException(
                    "設定 " + name + " 太短：去前後空白後須至少 " + MIN_KEY_LENGTH + " 字（例：openssl rand -hex 32）");
        }
    }
}
