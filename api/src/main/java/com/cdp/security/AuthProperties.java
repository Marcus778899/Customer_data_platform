package com.cdp.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * cdp.security.*：攝取金鑰與管理金鑰（auth.md §1）；只從環境變數來，不得寫預設值；session 逾時在
 * server.servlet.session.timeout。toString 不輸出機密；啟動驗證見 {@link AuthPropertiesValidator}。
 */
@ConfigurationProperties("cdp.security")
public record AuthProperties(String ingestApiKey, String adminApiKey) {

    @Override
    public String toString() {
        return "AuthProperties[ingestApiKey=***, adminApiKey=***]";
    }
}
