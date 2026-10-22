package com.cdp.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class LoginRateLimiterTest {

    /** 可手動撥動的時鐘。 */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-22T00:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MutableClock clock = new MutableClock();
    private final LoginRateLimiter limiter = new LoginRateLimiter(new LoginProperties(3, Duration.ofSeconds(10)), clock);

    @Test
    void locks_when_count_reaches_threshold_until_last_failure_plus_lockout() {
        limiter.recordFailure("a");
        limiter.recordFailure("a");
        assertThat(limiter.remainingLock("a")).isZero();
        limiter.recordFailure("a");
        assertThat(limiter.remainingLock("a")).isEqualTo(Duration.ofSeconds(10));
        clock.advance(Duration.ofSeconds(4));
        assertThat(limiter.remainingLock("a")).isEqualTo(Duration.ofSeconds(6));
        clock.advance(Duration.ofSeconds(6));
        assertThat(limiter.remainingLock("a")).isZero();
    }

    @Test
    void failures_while_locked_do_not_extend_the_lock() {
        for (int i = 0; i < 3; i++) limiter.recordFailure("a");
        clock.advance(Duration.ofSeconds(5));
        limiter.recordFailure("a");
        assertThat(limiter.remainingLock("a")).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void count_restarts_when_last_failure_is_older_than_lockout() {
        limiter.recordFailure("a");
        limiter.recordFailure("a");
        clock.advance(Duration.ofSeconds(11));
        limiter.recordFailure("a");
        limiter.recordFailure("a");
        assertThat(limiter.remainingLock("a")).isZero();
        limiter.recordFailure("a");
        assertThat(limiter.remainingLock("a")).isPositive();
    }

    @Test
    void reset_and_keys_are_independent() {
        for (int i = 0; i < 3; i++) limiter.recordFailure("a");
        assertThat(limiter.remainingLock("b")).isZero();
        limiter.reset("a");
        assertThat(limiter.remainingLock("a")).isZero();
    }

    @Test
    void retry_after_rounds_up_to_whole_seconds() {
        assertThat(LoginRateLimiter.retryAfterSeconds(Duration.ofMillis(3001))).isEqualTo(4);
        assertThat(LoginRateLimiter.retryAfterSeconds(Duration.ofMillis(4000))).isEqualTo(4);
        assertThat(LoginRateLimiter.retryAfterSeconds(Duration.ofNanos(1))).isEqualTo(1);
        assertThat(LoginRateLimiter.retryAfterSeconds(Duration.ofMillis(2400))).isEqualTo(3);
    }

    @Test
    void invalid_settings_fail_startup_with_property_name() {
        assertThatThrownBy(() -> new LoginRateLimiter(new LoginProperties(0, Duration.ofSeconds(1)), clock))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cdp.login.max-failures");
        assertThatThrownBy(() -> new LoginRateLimiter(new LoginProperties(1, Duration.ZERO), clock))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cdp.login.lockout");
        assertThatThrownBy(() -> new LoginRateLimiter(new LoginProperties(1, null), clock))
                .isInstanceOf(IllegalStateException.class);
    }
}
