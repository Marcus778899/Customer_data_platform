package com.cdp.ingest;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * cdp.engine.dispatch.*：事件派發重試（spec 07 §11）。maxAttempts 為總共嘗試次數。
 *
 * <p>啟動驗證在建構子做：訊息只寫設定名稱、不帶值（Binder／Bean Validation 預設訊息會帶出被拒絕的值）。
 */
@ConfigurationProperties("cdp.engine.dispatch")
public record DispatchProperties(int maxAttempts, Duration backoffInitial, Duration backoffMax) {

    public DispatchProperties {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("cdp.engine.dispatch.max-attempts must be at least 1");
        }
        if (backoffInitial == null || backoffInitial.isNegative()) {
            throw new IllegalArgumentException(
                    "cdp.engine.dispatch.backoff-initial must be set and not negative");
        }
        if (backoffMax == null || backoffMax.compareTo(backoffInitial) < 0) {
            throw new IllegalArgumentException(
                    "cdp.engine.dispatch.backoff-max must be set and not less than"
                            + " cdp.engine.dispatch.backoff-initial");
        }
    }
}
