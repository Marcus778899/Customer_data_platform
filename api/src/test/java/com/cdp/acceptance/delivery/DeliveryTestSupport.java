package com.cdp.acceptance.delivery;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import com.cdp.acceptance.support.SharedContainers;
import com.cdp.common.fault.FaultInjector;
import com.cdp.common.fault.FaultPoint;

/**
 * BE-02 驗收接縫：Testcontainers（Postgres、RabbitMQ，使用真實 {@code RabbitTopology}）、可調 Clock、可編程
 * FaultInjector（以 {@code @Primary} 覆蓋 NoopFaultInjector）。
 */
@TestConfiguration(proxyBeanMethods = false)
public class DeliveryTestSupport {

    public static final Instant BASE = Instant.parse("2026-10-18T02:00:00Z");

    @Bean
    DynamicPropertyRegistrar sharedContainers(Environment env) {
        return SharedContainers.registrar("PT2S".equals(env.getProperty("cdp.delivery.requeue-delay")));
    }

    @Bean
    @ServiceConnection
    @ConditionalOnProperty(name = "cdp.delivery.requeue-delay", havingValue = "PT2S")
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer("postgres:18-alpine");
    }

    @Bean
    @ServiceConnection
    @ConditionalOnProperty(name = "cdp.delivery.requeue-delay", havingValue = "PT2S")
    RabbitMQContainer rabbit() {
        return new RabbitMQContainer("rabbitmq:4.3-management-alpine");
    }

    @Bean
    @Primary
    MutableClock testClock() {
        return new MutableClock(BASE);
    }

    @Bean
    @Primary
    ProgrammableFaultInjector testFaults() {
        return new ProgrammableFaultInjector();
    }

    /** 對指定 (點, idempotencyKey) 丟例外；disarm 後恢復正常。 */
    public static final class ProgrammableFaultInjector implements FaultInjector {
        private final Map<String, Boolean> armed = new ConcurrentHashMap<>();

        public void arm(FaultPoint point, String key) {
            armed.put(point + "|" + key, Boolean.TRUE);
        }

        public void disarm(FaultPoint point, String key) {
            armed.remove(point + "|" + key);
        }

        @Override
        public void at(FaultPoint point, String idempotencyKey) {
            if (armed.containsKey(point + "|" + idempotencyKey)) {
                throw new InjectedFault(point + " @ " + idempotencyKey);
            }
        }
    }

    public static final class InjectedFault extends RuntimeException {
        InjectedFault(String message) {
            super(message);
        }
    }

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
