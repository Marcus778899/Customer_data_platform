package com.cdp.query;

import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * 契約 §4.4：對 EnrollmentDetail 的 variables、trigger_snapshot.properties、trigger_snapshot.profile_snapshot、
 * trace[].evaluated 逐葉遮罩，並填 maskedPaths。所有角色都套用（v0.1.0 無解除遮罩權限）。
 */
@Component
public class PiiMasker {

    /** 引擎產生、不含個資的 evaluated 鍵，恆為明文（契約 §4.4）。 */
    public static final Set<String> ENGINE_KEYS =
            Set.of(
                    "event_id",
                    "event_name",
                    "duration",
                    "wake_at",
                    "template_id",
                    "channel_id",
                    "idempotency_key");

    private final MaskingProperties properties;

    public PiiMasker(MaskingProperties properties) {
        this.properties = properties;
    }

    /** 回傳遮罩後的新物件（maskedPaths 已填）；不修改輸入。 */
    public EnrollmentDetail mask(EnrollmentDetail raw) {
        throw new UnsupportedOperationException("TODO(BE-03)");
    }
}
