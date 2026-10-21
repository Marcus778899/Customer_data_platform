package com.cdp.acceptance.relayloop;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import com.cdp.common.fault.FaultInjector;
import com.cdp.common.fault.FaultPoint;

/**
 * relay-loop-rewrite 驗收接縫：Testcontainers（真實 RabbitTopology）、可調 Clock、
 * 可編程 FaultInjector（丟例外，或一次性阻塞到測試放行，用來製造「遲到的 confirm」）。
 */
@TestConfiguration(proxyBeanMethods = false)
public class RelayLoopTestSupport {

    public static final Instant BASE = Instant.parse("2026-10-18T02:00:00Z");

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer("postgres:18-alpine");
    }

    @Bean
    @ServiceConnection
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

    public static final class Gate {
        final CountDownLatch arrived = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        public boolean awaitArrived(Duration d) throws InterruptedException {
            return arrived.await(d.toMillis(), TimeUnit.MILLISECONDS);
        }

        public void release() {
            release.countDown();
        }
    }

    public static final class ProgrammableFaultInjector implements FaultInjector {
        private final Map<String, Boolean> armed = new ConcurrentHashMap<>();
        private final Map<String, Gate> gates = new ConcurrentHashMap<>();

        /** 對指定 (點, key) 丟例外，直到 disarm。 */
        public void arm(FaultPoint point, String key) {
            armed.put(point + "|" + key, Boolean.TRUE);
        }

        public void disarm(FaultPoint point, String key) {
            armed.remove(point + "|" + key);
        }

        /** 第一個到達 (點, key) 的執行緒阻塞到 {@link Gate#release()}；之後的到達者不受影響（一次性）。 */
        public Gate blockOnce(FaultPoint point, String key) {
            Gate g = new Gate();
            gates.put(point + "|" + key, g);
            return g;
        }

        public void reset() {
            armed.clear();
            gates.values().forEach(Gate::release);
            gates.clear();
        }

        @Override
        public void at(FaultPoint point, String key) {
            String id = point + "|" + key;
            Gate g = gates.remove(id);
            if (g != null) {
                g.arrived.countDown();
                try {
                    g.release.await(60, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (armed.containsKey(id)) {
                throw new InjectedFault(id);
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
