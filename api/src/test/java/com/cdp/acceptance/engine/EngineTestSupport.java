package com.cdp.acceptance.engine;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistrar;

import com.cdp.acceptance.support.SharedContainers;

/** 驗收測試共用接縫（DESIGN-01 做法 #5）：Testcontainers（使用真實 RabbitTopology）與可調 Clock。 */
@TestConfiguration(proxyBeanMethods = false)
public class EngineTestSupport {

    public static final Instant BASE = Instant.parse("2026-10-18T02:00:00Z");
    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    @Bean
    DynamicPropertyRegistrar sharedContainers(Environment env) {
        return SharedContainers.registrar(false);
    }

    @Bean
    @Primary
    MutableClock testClock() {
        return new MutableClock(BASE);
    }

    public static String ulid() {
        StringBuilder sb = new StringBuilder("01");
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < 24; i++) {
            sb.append(ALPHABET.charAt(r.nextInt(32)));
        }
        return sb.toString();
    }

    /** 可快轉的 Clock；測試 @BeforeEach 呼叫 reset()。 */
    public static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        public void reset() {
            now = BASE;
        }

        public void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
