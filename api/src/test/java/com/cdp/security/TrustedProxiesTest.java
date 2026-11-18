package com.cdp.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class TrustedProxiesTest {

    @Test
    void dash_means_none_and_regex_never_matches() {
        TrustedProxies t = TrustedProxies.parse(" - ");
        assertThat(t.isNone()).isTrue();
        assertThat(t.toInternalProxiesRegex()).isEqualTo("(?!)");
        assertThat("127.0.0.1".matches(t.toInternalProxiesRegex())).isFalse();
        assertThat("".matches(t.toInternalProxiesRegex())).isFalse();
    }

    @Test
    void exact_addresses_match_in_tomcat_notation() {
        Pattern p = Pattern.compile(TrustedProxies.parse("127.0.0.1, ::1,::ffff:192.0.2.1").toInternalProxiesRegex());
        assertThat(p.matcher("127.0.0.1").matches()).isTrue();
        assertThat(p.matcher("0:0:0:0:0:0:0:1").matches()).isTrue();
        assertThat(p.matcher("0:0:0:0:0:0:0:1%lo").matches()).isTrue();
        assertThat(p.matcher("192.0.2.1").matches()).isTrue();
        assertThat(p.matcher("127.0.0.11").matches()).isFalse();
        assertThat(p.matcher("127x0x0x1").matches()).isFalse();
        assertThat(TrustedProxies.parse("127.0.0.1").isNone()).isFalse();
    }

    @Test
    void rejects_invalid_values_naming_variable_and_item_without_echo() {
        for (String bad : new String[] {
            null, "", "  ", "CHANGE_ME", "10.0.0.0/8", "proxy.example", "[::1]", "fe80::1%eth0", "0.0.0.0", "::",
            "256.1.1.1", "1.2.3", "1.2.3.4:80", "-,127.0.0.1", "127.0.0.1,-", "127.0.0.1,", "a,b"
        }) {
            assertThatThrownBy(() -> TrustedProxies.parse(bad))
                    .as("value %s", bad)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("CDP_TRUSTED_PROXIES");
        }
        assertThatThrownBy(() -> TrustedProxies.parse("127.0.0.1,proxy.secret-host"))
                .hasMessageContaining("2")
                .hasMessageNotContaining("secret-host");
    }

    @Test
    void rejects_more_than_ten_items() {
        String eleven = "192.0.2.1,192.0.2.2,192.0.2.3,192.0.2.4,192.0.2.5,192.0.2.6,192.0.2.7,192.0.2.8,192.0.2.9,192.0.2.10,192.0.2.11";
        assertThatThrownBy(() -> TrustedProxies.parse(eleven)).isInstanceOf(IllegalStateException.class);
        assertThat(TrustedProxies.parse(eleven.substring(0, eleven.lastIndexOf(','))).isNone()).isFalse();
    }
}
