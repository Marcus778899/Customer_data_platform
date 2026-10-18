package com.cdp.ingest;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** cdp.engine.dispatch.*：事件派發重試（spec 07 §11）。maxAttempts 為總共嘗試次數。 */
@ConfigurationProperties("cdp.engine.dispatch")
public record DispatchProperties(int maxAttempts, Duration backoffInitial, Duration backoffMax) {}
