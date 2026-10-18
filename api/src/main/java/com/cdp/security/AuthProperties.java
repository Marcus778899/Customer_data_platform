package com.cdp.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * cdp.security.*：金鑰與客服帳密（變數見 DESIGN-01「設定」）；全部只從環境變數來，不得寫預設值；session 逾時在
 * server.servlet.session.timeout。toString 不輸出機密；啟動驗證見 {@link AuthPropertiesValidator}。
 */
@ConfigurationProperties("cdp.security")
public record AuthProperties(
        String ingestApiKey,
        String adminApiKey,
        String supportUsername,
        String supportPassword) {

    @Override
    public String toString() {
        return "AuthProperties[ingestApiKey=***, adminApiKey=***, supportUsername=***, supportPassword=***]";
    }
}
