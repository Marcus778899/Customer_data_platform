package com.cdp.security;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/** 啟動時驗證 cdp.security.*：金鑰不得空白、兩把金鑰不得相同。訊息只講設定名稱，不含值。 */
@Component
class AuthPropertiesValidator implements InitializingBean {

    private final AuthProperties properties;

    AuthPropertiesValidator(AuthProperties properties) {
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        require(properties.ingestApiKey(), "cdp.security.ingest-api-key（CDP_INGEST_API_KEY）");
        require(properties.adminApiKey(), "cdp.security.admin-api-key（CDP_ADMIN_API_KEY）");
        if (properties.ingestApiKey().equals(properties.adminApiKey())) {
            throw new IllegalStateException(
                    "cdp.security.ingest-api-key 與 cdp.security.admin-api-key 不可相同（兩把金鑰必須不同）");
        }
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("設定 " + name + " 不可為空");
        }
    }
}
