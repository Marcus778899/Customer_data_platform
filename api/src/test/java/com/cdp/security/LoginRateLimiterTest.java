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

    private void fail(String key) {
        assertThat(limiter.tryBegin(key)).isEmpty();
        limiter.finish(key, LoginRateLimiter.Outcome.FAILURE);
    }

    private Duration locked(String key) {
        return limiter.tryBegin(key).orElse(Duration.ZERO);
    }

    @Test
    void locks_when_count_reaches_threshold_until_last_failure_plus_lockout() {
        fail("a");
        fail("a");
        fail("a");
        assertThat(locked("a")).isEqualTo(Duration.ofSeconds(10));
        clock.advance(Duration.ofSeconds(4));
        assertThat(locked("a")).isEqualTo(Duration.ofSeconds(6));
        clock.advance(Duration.ofSeconds(6));
        assertThat(limiter.tryBegin("a")).isEmpty();
    }

    @Test
    void attempts_while_locked_do_not_extend_the_lock() {
        for (int i = 0; i < 3; i++) fail("a");
        clock.advance(Duration.ofSeconds(5));
        assertThat(locked("a")).isEqualTo(Duration.ofSeconds(5));
        assertThat(locked("a")).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void count_restarts_when_last_failure_is_older_than_lockout() {
        fail("a");
        fail("a");
        clock.advance(Duration.ofSeconds(11));
        fail("a");
        fail("a");
        assertThat(locked("a")).isZero();
        limiter.finish("a", LoginRateLimiter.Outcome.ABORTED);
    }

    @Test
    void success_resets_and_keys_are_independent() {
        fail("a");
        fail("a");
        assertThat(limiter.tryBegin("a")).isEmpty();
        limiter.finish("a", LoginRateLimiter.Outcome.SUCCESS);
        fail("a");
        fail("a");
        assertThat(limiter.tryBegin("b")).isEmpty();
    }

    @Test
    void aborted_attempt_releases_slot_without_counting() {
        for (int i = 0; i < 10; i++) {
            assertThat(limiter.tryBegin("a")).isEmpty();
            limiter.finish("a", LoginRateLimiter.Outcome.ABORTED);
        }
        assertThat(limiter.tryBegin("a")).isEmpty();
    }

    @Test
    void in_flight_attempts_count_toward_the_threshold() {
        assertThat(limiter.tryBegin("a")).isEmpty();
        assertThat(limiter.tryBegin("a")).isEmpty();
        assertThat(limiter.tryBegin("a")).isEmpty();
        assertThat(limiter.tryBegin("a")).isPresent();
    }

    @Test
    void concurrent_wrong_passwords_never_admit_more_than_the_threshold() throws Exception {
        int threads = 40;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger admitted = new java.util.concurrent.atomic.AtomicInteger();
        java.util.List<java.util.concurrent.Future<?>> fs = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            fs.add(pool.submit(() -> {
                go.await();
                if (limiter.tryBegin("race").isEmpty()) {
                    admitted.incrementAndGet();
                    Thread.sleep(50); // 模擬 BCrypt
                    limiter.finish("race", LoginRateLimiter.Outcome.FAILURE);
                }
                return null;
            }));
        }
        go.countDown();
        for (var f : fs) f.get();
        pool.shutdown();
        assertThat(admitted.get()).isEqualTo(3);
        assertThat(limiter.tryBegin("race")).isPresent();
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
