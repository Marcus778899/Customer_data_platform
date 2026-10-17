package com.cdp.query;

import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** cdp.trace.plain-keys：可明文顯示的屬性鍵（契約 §4.4）；空 = 全部遮罩。 */
@ConfigurationProperties("cdp.trace")
public record MaskingProperties(Set<String> plainKeys) {}
