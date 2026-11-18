package com.cdp.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientAddressTest {

    private static String key(String remote) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr(remote);
        req.addHeader("X-Forwarded-For", "1.2.3.4");
        return ClientAddress.of(req);
    }

    @Test
    void ipv4_is_full_address_and_ignores_forwarded_header() {
        assertThat(key("203.0.113.9")).isEqualTo("203.0.113.9");
    }

    @Test
    void ipv4_mapped_equals_ipv4() {
        assertThat(key("::ffff:203.0.113.9")).isEqualTo("203.0.113.9");
    }

    @Test
    void ipv6_is_truncated_to_64() {
        assertThat(key("2001:db8:1:2:aaaa:bbbb:cccc:dddd")).isEqualTo("2001:db8:1:2::/64");
        assertThat(key("2001:DB8:1:2::1")).isEqualTo(key("2001:db8:1:2:0:0:0:ffff"));
        assertThat(key("2001:db8:1:3::1")).isNotEqualTo(key("2001:db8:1:2::1"));
        assertThat(key("::1")).isEqualTo("0:0:0:0::/64");
    }

    @Test
    void zone_id_is_dropped() {
        assertThat(key("fe80::1%eth0")).isEqualTo("fe80:0:0:0::/64");
    }

    @Test
    void blank_null_or_garbage_becomes_unknown() {
        assertThat(key(" ")).isEqualTo("unknown");
        assertThat(key("not-an-ip")).isEqualTo("unknown");
        assertThat(key("1.2.3.4:80")).isEqualTo("unknown");
    }
}
