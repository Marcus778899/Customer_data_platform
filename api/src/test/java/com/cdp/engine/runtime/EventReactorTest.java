package com.cdp.engine.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.cdp.engine.StateStore;
import com.cdp.engine.model.EnrollmentState;
import com.cdp.engine.model.EnrollmentStatus;
import com.cdp.engine.model.TriggerSnapshot;
import com.cdp.engine.model.WakeCondition;
import com.cdp.engine.model.WakeSignal;
import com.cdp.journey.JourneyDefinition;
import com.cdp.journey.JourneyDefinitionStore;
import com.cdp.journey.JourneyStatus;

class EventReactorTest {

    private static final Instant T0 = Instant.parse("2026-11-01T00:00:00Z");

    private final StateStore store = mock(StateStore.class);
    private final JourneyDefinitionStore journeys = mock(JourneyDefinitionStore.class);
    private final EnrollmentAdvancer advancer = mock(EnrollmentAdvancer.class);
    private final EventReactor reactor = new EventReactor(store, journeys, advancer);

    private EnrollmentState enrollment(String id, String triggerEventId, EnrollmentStatus status, WakeCondition wake) {
        return new EnrollmentState(
                id, "j", 1, "p", status, "wait_1", wake,
                new TriggerSnapshot(triggerEventId, "in", T0, "CUSTOMER_BEHAVIOR", Map.of(), Map.of()),
                null, Map.of(), List.of(), T0, T0, null, null);
    }

    private void definition(Map<String, Object> settings) {
        when(journeys.find("j", 1))
                .thenReturn(Optional.of(
                        new JourneyDefinition("j", 1, "n", null, JourneyStatus.ACTIVE, settings, "start", List.of(), T0, T0)));
    }

    private void open(EnrollmentState s) {
        when(store.findOpenByProfile("p")).thenReturn(List.of(s));
        when(store.lockById(s.enrollmentId())).thenReturn(Optional.of(s));
    }

    @Test
    void matchingEventWakesWaiter_filterMismatchDoesNot() {
        var wake =
                new WakeCondition.Event(
                        "paid",
                        Map.of("type", "attribute", "field", "properties.amount", "operator", "GT", "value", 500),
                        null);
        EnrollmentState s = enrollment("e1", "other", EnrollmentStatus.WAITING, wake);
        open(s);
        definition(Map.of());
        assertThat(reactor.react("p", "ev", "paid", T0, Map.of("amount", 100))).isNull();
        verify(advancer, never()).advance(any(), any());
        assertThat(reactor.react("p", "ev", "paid", T0, Map.of("amount", 900))).isNull();
        verify(advancer).advance(eq(s), any(WakeSignal.EventArrived.class));
        reactor.react("p", "ev", "different", T0, Map.of());
        verify(advancer, times(1)).advance(any(), any());
    }

    @Test
    void settingsEventTerminates_andOwnTriggerEventIsSkipped() {
        EnrollmentState s = enrollment("e1", "ev", EnrollmentStatus.WAITING, new WakeCondition.Timer(T0));
        open(s);
        definition(Map.of("goal", Map.of("condition", Map.of("type", "event", "event", "paid"))));
        reactor.react("p", "ev", "paid", T0, Map.of());
        verify(advancer, never()).terminateBySettings(any(), any(), any());
        reactor.react("p", "ev2", "paid", T0, Map.of());
        verify(advancer).terminateBySettings(eq(s), eq(EnrollmentStatus.GOAL_MET), any());
    }

    @Test
    void terminalMissingOrFailingEnrollmentsAreHandled() {
        EnrollmentState done = enrollment("e1", "x", EnrollmentStatus.COMPLETED, null);
        open(done);
        assertThat(reactor.react("p", "ev", "paid", T0, Map.of())).isNull();
        when(store.lockById("e1")).thenReturn(Optional.empty());
        assertThat(reactor.react("p", "ev", "paid", T0, Map.of())).isNull();

        EnrollmentState s = enrollment("e2", "x", EnrollmentStatus.WAITING, new WakeCondition.Event("paid", null, null));
        open(s);
        when(journeys.find("j", 1)).thenReturn(Optional.empty());
        assertThat(reactor.react("p", "ev", "paid", T0, Map.of())).isNull();
        definition(Map.of());
        when(advancer.advance(any(), any())).thenThrow(new IllegalStateException("boom"));
        assertThat(reactor.react("p", "ev", "paid", T0, Map.of())).isInstanceOf(IllegalStateException.class);
    }
}
