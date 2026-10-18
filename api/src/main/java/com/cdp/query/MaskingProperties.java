package com.cdp.query;

import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** cdp.trace.plain-keys：可明文顯示的屬性鍵（契約 §4.4）；空 = 全部遮罩。 */
@ConfigurationProperties("cdp.trace")
public record MaskingProperties(Set<String> plainKeys) {
    /** 去前後空白，捨棄空字串與 "-"（"-" 代表全部遮罩）。 */
    public MaskingProperties {
        plainKeys =
                plainKeys == null
                        ? Set.of()
                        : plainKeys.stream()
                                .filter(k -> k != null)
                                .map(String::trim)
                                .filter(k -> !k.isEmpty() && !k.equals("-"))
                                .collect(Collectors.toUnmodifiableSet());
    }
}
