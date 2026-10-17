package com.cdp.delivery.relay;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** cdp.relay.*（變數見 DESIGN-01「設定」）。 */
@ConfigurationProperties("cdp.relay")
public record RelayProperties(Duration pollInterval, int batchSize) {}
