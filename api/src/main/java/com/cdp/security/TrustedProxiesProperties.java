package com.cdp.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 信任的反向代理（auth.md §3「來源 IP」，CDP_TRUSTED_PROXIES）。原始字串，格式驗證見 {@link TrustedProxies}。
 * 無預設值：未設定時佔位符無法解析，後端拒絕啟動。
 */
@ConfigurationProperties("cdp.security")
public record TrustedProxiesProperties(String trustedProxies) {}
