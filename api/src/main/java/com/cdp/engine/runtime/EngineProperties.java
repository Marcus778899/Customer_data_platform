package com.cdp.engine.runtime;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** cdp.engine.*：推進迴圈與事件派發的輪詢間隔、每批筆數（變數見 DESIGN-01「設定」）。 */
@ConfigurationProperties("cdp.engine")
public record EngineProperties(Duration pollInterval, int batchSize) {}
