package com.cdp.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;

class MaskingPropertiesTest {

    @Test
    void nullFallsBackToBuiltInName() {
        assertThat(new MaskingProperties(null).plainKeys()).containsExactly("name");
    }

    @Test
    void configuredReplacesAndDashOrBlankMeansNone() {
        assertThat(new MaskingProperties(Set.of(" email ", "")).plainKeys()).containsExactly("email");
        assertThat(new MaskingProperties(Set.of("-")).plainKeys()).isEmpty();
        assertThat(new MaskingProperties(Set.of()).plainKeys()).isEmpty();
    }
}
