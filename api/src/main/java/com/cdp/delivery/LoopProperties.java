package com.cdp.delivery;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** cdp.loop.*：三個輪詢迴圈共用，滿批續做的時間上限（CDP_LOOP_MAX_DRAIN）。 */
@ConfigurationProperties("cdp.loop")
public record LoopProperties(Duration maxDrain) {}
