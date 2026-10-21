package com.cdp.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import com.cdp.delivery.LoopProperties;
import com.cdp.engine.runtime.EngineProperties;
import com.cdp.journey.EventSourceBinding;
import com.cdp.journey.JourneyDefinitionStore;

class EventDispatcherUnparsableRowTest {

    private static final Instant NOW = Instant.parse("2026-10-18T00:00:00Z");

    private static NormalizedEvent goodEvent() {
        return new NormalizedEvent(
                "good",
                SourceType.valueOf(SourceType.values()[0].name()),
                "src",
                "evt",
                NOW,
                NOW,
                new EventIdentity("p1", List.of()),
                null,
                null);
    }

    @Test
    void unparsableRowIsExhaustedAndOthersStillDispatched() {
        List<String> calls = new ArrayList<>();
        List<UndispatchedEvent> pending = new ArrayList<>(List.of(
                new UndispatchedEvent("bad", null, "parse boom"),
                new UndispatchedEvent("good", goodEvent(), null)));
        EventStore store = (EventStore) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {EventStore.class},
                (p, m, a) -> switch (m.getName()) {
                    case "lockUndispatched" -> List.copyOf(pending);
                    case "recordFailure" -> {
                        calls.add("fail:" + a[0]);
                        yield 1;
                    }
                    case "markDispatched" -> {
                        calls.add("mark:" + a[0] + ":" + a[2]);
                        pending.removeIf(r -> r.eventId().equals(a[0]));
                        yield null;
                    }
                    default -> throw new UnsupportedOperationException(m.getName());
                });
        JourneyDefinitionStore journeys = (JourneyDefinitionStore) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {JourneyDefinitionStore.class},
                (p, m, a) -> {
                    if (m.getName().equals("findActiveEventSources")) {
                        return List.<EventSourceBinding>of();
                    }
                    throw new UnsupportedOperationException(m.getName());
                });
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        EventDispatcher d = new EventDispatcher(
                store,
                journeys,
                null,
                new EngineProperties(Duration.ofSeconds(1), 10),
                new DispatchProperties(5, Duration.ofSeconds(10), Duration.ofMinutes(10)),
                new LoopProperties(Duration.ofSeconds(10)),
                new TransactionTemplate(txManager),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(d.runOnce()).isEqualTo(2);
        assertThat(calls).containsExactly("fail:bad", "mark:bad:EXHAUSTED", "mark:good:DISPATCHED");
    }
}
