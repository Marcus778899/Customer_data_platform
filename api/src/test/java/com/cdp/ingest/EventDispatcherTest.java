package com.cdp.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import com.cdp.delivery.LoopProperties;
import com.cdp.engine.JourneyRuntime;
import com.cdp.engine.runtime.EngineProperties;
import com.cdp.engine.runtime.EventReactor;
import com.cdp.journey.EventSourceBinding;
import com.cdp.journey.JourneyDefinition;
import com.cdp.journey.JourneyDefinitionStore;
import com.cdp.journey.JourneyNodeDefinition;
import com.cdp.journey.JourneyStatus;

/** 入口 event_filter（D-085）與事件反應的串接。 */
class EventDispatcherTest {

    private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");
    private static final Map<String, Object> FILTER =
            Map.of("type", "attribute", "field", "properties.amount", "operator", "GT", "value", 500);

    private final EventStore store = mock(EventStore.class);
    private final JourneyDefinitionStore journeys = mock(JourneyDefinitionStore.class);
    private final JourneyRuntime runtime = mock(JourneyRuntime.class);
    private final EventReactor reactor = mock(EventReactor.class);

    private EventDispatcher dispatcher() {
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return new EventDispatcher(
                store, journeys, runtime,
                new EngineProperties(Duration.ofSeconds(1), 10),
                new DispatchProperties(5, Duration.ofSeconds(10), Duration.ofMinutes(10)),
                new LoopProperties(Duration.ofSeconds(10)),
                new TransactionTemplate(tm),
                Clock.fixed(NOW, ZoneOffset.UTC),
                reactor);
    }

    private NormalizedEvent event(Map<String, Object> props) {
        return new NormalizedEvent(
                "ev1", SourceType.values()[0], "src", "cart", NOW, NOW,
                new EventIdentity("p1", List.of()), props, null);
    }

    private void arrange(Object filter, Map<String, Object> props) {
        UndispatchedEvent row = new UndispatchedEvent("ev1", event(props), null);
        when(store.lockUndispatched(anyInt(), any())).thenReturn(List.of(row)).thenReturn(List.of());
        when(journeys.findActiveEventSources("cart")).thenReturn(List.of(new EventSourceBinding("j", 1, "src_1")));
        Map<String, Object> cfg = new java.util.HashMap<>();
        cfg.put("event_name", "cart");
        cfg.put("event_filter", filter);
        JourneyDefinition def =
                new JourneyDefinition("j", 1, "n", null, JourneyStatus.ACTIVE, Map.of(), "start",
                        List.of(new JourneyNodeDefinition("src_1", "EventSourceNode", null, cfg, Map.of())), NOW, NOW);
        when(journeys.find("j", 1)).thenReturn(Optional.of(def));
    }

    @Test
    void entryFilterMismatch_doesNotStart_butReactorStillRuns() {
        arrange(FILTER, Map.of("amount", 100));
        assertThat(dispatcher().runOnce()).isEqualTo(1);
        verify(runtime, never()).start(anyString(), anyInt(), anyString(), anyString(), any(), any());
        verify(reactor).react(eq("p1"), eq("ev1"), eq("cart"), eq(NOW), any());
        verify(store).markDispatched("ev1", NOW, "DISPATCHED");
    }

    @Test
    void entryFilterMatch_andNoFilter_start() {
        arrange(FILTER, Map.of("amount", 900));
        dispatcher().runOnce();
        verify(runtime, times(1)).start(eq("j"), eq(1), eq("src_1"), eq("p1"), any(), isNull());
    }

    @Test
    void nullFilterAndNullPropertiesMeansNoFilter() {
        arrange(null, null);
        dispatcher().runOnce();
        verify(runtime).start(eq("j"), eq(1), eq("src_1"), eq("p1"), any(), isNull());
    }

    @Test
    void reactorFailureSchedulesRetry() {
        arrange(null, Map.of());
        when(reactor.react(any(), any(), any(), any(), any())).thenReturn(new IllegalStateException("boom"));
        when(store.recordFailure(eq("ev1"), any())).thenReturn(1);
        assertThat(dispatcher().runOnce()).isEqualTo(1);
        verify(store).scheduleRetry(eq("ev1"), any());
        verify(store, never()).markDispatched(eq("ev1"), any(), eq("DISPATCHED"));
    }

    private NormalizedEvent lateEvent() {
        return new NormalizedEvent(
                "ev1", SourceType.values()[0], "src", "cart", NOW.minus(Duration.ofHours(30)), NOW,
                new EventIdentity("p1", List.of()), Map.of(), null);
    }

    @Test
    void lateEvent_doesNotStart_butStillReacts_andIsMarkedLate() {
        when(store.lockUndispatched(anyInt(), any()))
                .thenReturn(List.of(new UndispatchedEvent("ev1", lateEvent(), null)))
                .thenReturn(List.of());
        assertThat(dispatcher().runOnce()).isEqualTo(1);
        verify(journeys, never()).findActiveEventSources(anyString());
        verify(runtime, never()).start(anyString(), anyInt(), anyString(), anyString(), any(), any());
        verify(reactor).react(eq("p1"), eq("ev1"), eq("cart"), any(), any());
        verify(store).markDispatched("ev1", NOW, "LATE");
    }

    @Test
    void lateEvent_reactorFailureSchedulesRetry() {
        when(store.lockUndispatched(anyInt(), any()))
                .thenReturn(List.of(new UndispatchedEvent("ev1", lateEvent(), null)))
                .thenReturn(List.of());
        when(reactor.react(any(), any(), any(), any(), any())).thenReturn(new IllegalStateException("boom"));
        when(store.recordFailure(eq("ev1"), any())).thenReturn(1);
        dispatcher().runOnce();
        verify(store).scheduleRetry(eq("ev1"), any());
        verify(store, never()).markDispatched(eq("ev1"), any(), eq("LATE"));
    }
}
