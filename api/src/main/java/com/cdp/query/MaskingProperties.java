package com.cdp.query;

import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * cdp.trace.plain-keys：可明文顯示的屬性鍵（契約 §4.4）。未設定（null）= 內建預設 {name}（D-083）；
 * 有設定即取代內建；空或 "-" = 全部遮罩。
 */
@ConfigurationProperties("cdp.trace")
public record MaskingProperties(Set<String> plainKeys) {
    /** 內建預設：姓名明文，其餘（email、手機…）遮罩。 */
    public static final Set<String> DEFAULT_PLAIN_KEYS = Set.of("name");

    /** 去前後空白，捨棄空字串與 "-"（"-" 代表全部遮罩）。 */
    public MaskingProperties {
        plainKeys =
                plainKeys == null
                        ? DEFAULT_PLAIN_KEYS
                        : plainKeys.stream()
                                .filter(k -> k != null)
                                .map(String::trim)
                                .filter(k -> !k.isEmpty() && !k.equals("-"))
                                .collect(Collectors.toUnmodifiableSet());
    }
}
