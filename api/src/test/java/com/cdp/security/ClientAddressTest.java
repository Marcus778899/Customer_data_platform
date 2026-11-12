package com.cdp.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientAddressTest {

    @Test
    void uses_remote_addr_and_ignores_forwarded_header() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("203.0.113.9");
        req.addHeader("X-Forwarded-For", "1.2.3.4");
        assertThat(ClientAddress.of(req)).isEqualTo("203.0.113.9");
    }

    @Test
    void blank_address_becomes_unknown() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr(" ");
        assertThat(ClientAddress.of(req)).isEqualTo("unknown");
    }
}
