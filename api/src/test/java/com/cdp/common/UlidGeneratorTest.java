package com.cdp.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class UlidGeneratorTest {

    @Test
    void producesValidUlidsOrderedByTime() {
        Clock early = Clock.fixed(Instant.parse("2026-10-18T02:00:00Z"), ZoneOffset.UTC);
        Clock late = Clock.fixed(Instant.parse("2026-10-18T02:00:01Z"), ZoneOffset.UTC);
        String a = new UlidGenerator(early).newId();
        String b = new UlidGenerator(late).newId();
        assertThat(a).matches("[0-9A-HJKMNP-TV-Z]{26}");
        assertThat(b).matches("[0-9A-HJKMNP-TV-Z]{26}");
        assertThat(b).isGreaterThan(a);
        assertThat(new UlidGenerator(early).newId()).isNotEqualTo(a);
    }
}
