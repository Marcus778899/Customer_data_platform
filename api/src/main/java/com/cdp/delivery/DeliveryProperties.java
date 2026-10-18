package com.cdp.delivery;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** cdp.delivery.*：consumer 暫時性 DB 例外重排前的等待（DELIVERY_REQUEUE_DELAY）。 */
@ConfigurationProperties("cdp.delivery")
public record DeliveryProperties(Duration requeueDelay) {}
