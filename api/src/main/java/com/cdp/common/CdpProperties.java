package com.cdp.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** cdp.tenant-id：v0.1.0 單租戶，所有寫入與查詢都用這個 tenant_id（契約 §1）。 */
@ConfigurationProperties("cdp")
public record CdpProperties(String tenantId) {}
