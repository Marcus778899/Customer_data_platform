package com.cdp.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.apache.catalina.valves.RemoteIpValve;
import org.junit.jupiter.api.Test;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;

class TrustedProxiesTomcatCustomizerTest {

    @Test
    void replaces_internal_proxies_on_the_single_valve() {
        TomcatServletWebServerFactory f = new TomcatServletWebServerFactory();
        RemoteIpValve valve = new RemoteIpValve();
        valve.setInternalProxies("10[.].*");
        valve.setProtocolHeader("X-Forwarded-Proto");
        f.addEngineValves(valve);
        new TrustedProxiesTomcatCustomizer(TrustedProxies.parse("192.0.2.1")).customize(f);
        assertThat(valve.getInternalProxies()).contains("192.0.2.1").doesNotContain("10[.]");
        assertThat(valve.getTrustedProxies()).isNull();
        assertThat(valve.getProtocolHeader()).isEqualTo("X-Forwarded-Proto");
    }

    @Test
    void dash_installs_never_matching_pattern() {
        TomcatServletWebServerFactory f = new TomcatServletWebServerFactory();
        RemoteIpValve valve = new RemoteIpValve();
        f.addEngineValves(valve);
        new TrustedProxiesTomcatCustomizer(TrustedProxies.parse("-")).customize(f);
        assertThat(valve.getInternalProxies()).isEqualTo("(?!)");
    }

    @Test
    void inserts_normalizer_before_the_remote_ip_valve() {
        TomcatServletWebServerFactory f = new TomcatServletWebServerFactory();
        RemoteIpValve valve = new RemoteIpValve();
        f.addEngineValves(valve);
        new TrustedProxiesTomcatCustomizer(TrustedProxies.parse("-")).customize(f);
        assertThat(f.getEngineValves())
                .hasSize(2)
                .element(0)
                .isInstanceOf(TrustedProxiesTomcatCustomizer.ForwardedForNormalizer.class);
        assertThat(f.getEngineValves()).element(1).isSameAs(valve);
    }

    @Test
    void normalizer_rewrites_ip_text_and_keeps_other_tokens() {
        assertThat(TrustedProxiesTomcatCustomizer.ForwardedForNormalizer.normalize(" ::1 "))
                .isEqualTo("0:0:0:0:0:0:0:1");
        assertThat(TrustedProxiesTomcatCustomizer.ForwardedForNormalizer.normalize("::FFFF:192.0.2.1"))
                .isEqualTo("192.0.2.1");
        assertThat(TrustedProxiesTomcatCustomizer.ForwardedForNormalizer.normalize("unknown"))
                .isEqualTo("unknown");
    }

    @Test
    void no_valve_is_tolerated_and_duplicate_valve_fails() {
        TrustedProxiesTomcatCustomizer c = new TrustedProxiesTomcatCustomizer(TrustedProxies.parse("-"));
        TomcatServletWebServerFactory none = new TomcatServletWebServerFactory();
        c.customize(none);
        assertThat(none.getEngineValves()).isEmpty();
        TomcatServletWebServerFactory two = new TomcatServletWebServerFactory();
        two.addEngineValves(new RemoteIpValve(), new RemoteIpValve());
        assertThatThrownBy(() -> c.customize(two)).isInstanceOf(IllegalStateException.class);
    }
}
