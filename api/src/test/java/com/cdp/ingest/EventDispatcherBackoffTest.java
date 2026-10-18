package com.cdp.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;

import org.junit.jupiter.api.Test;

class EventDispatcherBackoffTest {

    private static EventDispatcher with(Duration initial, Duration max) {
        return new EventDispatcher(
                null, null, null, null, new DispatchProperties(5, initial, max), Clock.systemUTC());
    }

    @Test
    void doublesUntilCap() {
        EventDispatcher d = with(Duration.ofSeconds(10), Duration.ofMinutes(10));
        assertThat(d.backoff(1)).isEqualTo(Duration.ofSeconds(10));
        assertThat(d.backoff(2)).isEqualTo(Duration.ofSeconds(20));
        assertThat(d.backoff(3)).isEqualTo(Duration.ofSeconds(40));
        assertThat(d.backoff(7)).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void hugeAttemptCountDoesNotOverflow() {
        EventDispatcher d = with(Duration.ofSeconds(10), Duration.ofMinutes(10));
        assertThat(d.backoff(100_000)).isEqualTo(Duration.ofMinutes(10));
    }
}
