package com.cdp.delivery;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * cdp.delivery.*：requeueDelay = consumer 暫時性 DB 例外重排前的等待（DELIVERY_REQUEUE_DELAY）；shutdownTimeout =
 * listener container 關機時等在途訊息處理完的上限（DELIVERY_SHUTDOWN_TIMEOUT）。
 */
@ConfigurationProperties("cdp.delivery")
public record DeliveryProperties(Duration requeueDelay, Duration shutdownTimeout) {}
