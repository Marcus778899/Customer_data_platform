package com.cdp.engine.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionTemplate;

import com.cdp.delivery.LoopProperties;
import com.cdp.delivery.OutboxStore;
import com.cdp.engine.StateStore;
import com.cdp.engine.model.EnrollmentState;
import com.cdp.engine.model.EnrollmentStatus;
import com.cdp.engine.model.EnrollmentTrace;
import com.cdp.engine.model.TriggerSnapshot;
import com.cdp.journey.JourneyDefinition;
import com.cdp.journey.JourneyDefinitionStore;
import com.cdp.journey.JourneyNodeDefinition;
import com.cdp.journey.JourneyStatus;
import com.cdp.journey.JourneyStore;
import com.cdp.journey.JourneyStore.StoppedEnrollment;

class JourneyStopSweeperTest {

    private static final Instant NOW = Instant.parse("2026-11-08T01:00:00Z");

    private final JourneyStore journeys = mock(JourneyStore.class);
    private final JourneyDefinitionStore definitions = mock(JourneyDefinitionStore.class);
    private final StateStore stateStore = mock(StateStore.class);
    private final OutboxStore outbox = mock(OutboxStore.class);
    private final TransactionTemplate tx = new TransactionTemplate() {
        @Override
        public <T> T execute(org.springframework.transaction.support.TransactionCallback<T> action) {
            return action.doInTransaction(null);
        }
    };
    private final JourneyStopSweeper sweeper =
            new JourneyStopSweeper(
                    journeys,
                    definitions,
                    stateStore,
                    outbox,
                    new EngineProperties(Duration.ofSeconds(1), 10),
                    new LoopProperties(Duration.ofSeconds(1)),
                    tx,
                    Clock.fixed(NOW, ZoneOffset.UTC));

    private static EnrollmentState state(EnrollmentStatus status) {
        return new EnrollmentState(
                "e1", "j1", 1, "p1", status, "wait_1", null, mock(TriggerSnapshot.class), null, Map.of(), List.of(), NOW, NOW, null, null);
    }

    @Test
    void abortsOpenEnrollmentWithTraceAndCancelsOutbox() {
        when(journeys.lockOpenEnrollmentsOfStoppedJourneys(10)).thenReturn(List.of(new StoppedEnrollment("e1", "j1", 1, "活動取消")));
        when(stateStore.findById("e1")).thenReturn(Optional.of(state(EnrollmentStatus.WAITING)));
        JourneyDefinition def =
                new JourneyDefinition(
                        "j1",
                        1,
                        "n",
                        null,
                        JourneyStatus.ABORTED,
                        Map.of(),
                        "start",
                        List.of(new JourneyNodeDefinition("wait_1", "WaitDelayNode", null, Map.of(), Map.of())),
                        NOW,
                        NOW);
        when(definitions.find("j1", 1)).thenReturn(Optional.of(def));

        assertThat(sweeper.runOnce()).isEqualTo(1);

        ArgumentCaptor<EnrollmentState> saved = ArgumentCaptor.forClass(EnrollmentState.class);
        ArgumentCaptor<EnrollmentTrace> trace = ArgumentCaptor.forClass(EnrollmentTrace.class);
        verify(stateStore).save(saved.capture(), trace.capture(), eq(List.of()));
        assertThat(saved.getValue().status()).isEqualTo(EnrollmentStatus.ABORTED);
        assertThat(saved.getValue().terminationReason()).isEqualTo("旅程已停止：活動取消");
        assertThat(saved.getValue().terminatedAt()).isEqualTo(NOW);
        assertThat(saved.getValue().wakeCondition()).isNull();
        assertThat(trace.getValue().seq()).isEqualTo(DefaultEnrollmentAdvancer.APPEND_TRACE);
        assertThat(trace.getValue().nodeType()).isEqualTo("WaitDelayNode");
        verify(outbox).cancelPendingByEnrollment("e1");
    }

    @Test
    void skipsTerminalAndMissingAndTruncatesLongReason() {
        when(journeys.lockOpenEnrollmentsOfStoppedJourneys(10))
                .thenReturn(
                        List.of(
                                new StoppedEnrollment("gone", "j1", 1, "x"),
                                new StoppedEnrollment("done", "j1", 1, "x"),
                                new StoppedEnrollment("e1", "j1", 1, "長".repeat(200))));
        when(stateStore.findById("gone")).thenReturn(Optional.empty());
        when(stateStore.findById("done")).thenReturn(Optional.of(state(EnrollmentStatus.COMPLETED)));
        when(stateStore.findById("e1")).thenReturn(Optional.of(state(EnrollmentStatus.ACTIVE)));
        when(definitions.find("j1", 1)).thenReturn(Optional.empty());

        assertThat(sweeper.runOnce()).isEqualTo(1);

        ArgumentCaptor<EnrollmentState> saved = ArgumentCaptor.forClass(EnrollmentState.class);
        ArgumentCaptor<EnrollmentTrace> trace = ArgumentCaptor.forClass(EnrollmentTrace.class);
        verify(stateStore).save(saved.capture(), trace.capture(), any());
        assertThat(saved.getValue().terminationReason()).hasSize(200);
        assertThat(trace.getValue().nodeType()).isEqualTo("Unknown");
        verify(outbox, never()).cancelPendingByEnrollment("done");
    }

    @Test
    void tickDrainsUntilBatchNotFull() {
        when(journeys.lockOpenEnrollmentsOfStoppedJourneys(10)).thenReturn(List.of());
        sweeper.tick();
        verify(journeys).lockOpenEnrollmentsOfStoppedJourneys(10);
    }

    @Test
    void tickSwallowsFailures() {
        when(journeys.lockOpenEnrollmentsOfStoppedJourneys(10)).thenThrow(new IllegalStateException("db down"));
        sweeper.tick();
        verify(journeys).lockOpenEnrollmentsOfStoppedJourneys(10);
    }
}
