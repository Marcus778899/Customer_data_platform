package com.cdp.delivery.relay;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * cdp.relay.*（變數見 DESIGN-01、DESIGN-03「設定」）。confirmTimeout = OUTBOX_RELAY_CONFIRM_TIMEOUT，lease =
 * OUTBOX_RELAY_LEASE（必須大於 confirmTimeout，由 {@link com.cdp.delivery.LoopStartupValidator} 啟動時驗證）。
 */
@ConfigurationProperties("cdp.relay")
public record RelayProperties(
        Duration pollInterval,
        int batchSize,
        Duration retryDelay,
        Duration confirmTimeout,
        Duration lease) {}
