package com.cdp.journey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.cdp.common.web.ApiException;
import com.cdp.common.web.ErrorCode;
import com.cdp.delivery.OutboxStore;
import com.cdp.journey.JourneyStore.JourneyRow;

class JourneyStopServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-08T01:00:00Z");

    private final JourneyStore journeys = mock(JourneyStore.class);
    private final JourneyReviewStore reviews = mock(JourneyReviewStore.class);
    private final OutboxStore outbox = mock(OutboxStore.class);
    private final JourneyQueryStore queries = mock(JourneyQueryStore.class);
    private final JourneyStopService service =
            new JourneyStopService(journeys, reviews, outbox, queries, Clock.fixed(NOW, ZoneOffset.UTC));

    private static JourneyRow row(JourneyStatus status) {
        return new JourneyRow("j1", status, 1, null, null, 0, null, null, NOW, NOW, null, null, null);
    }

    private static JourneyDetail detail() {
        return new JourneyDetail(
                "j1", "n", JourneyStatus.ABORTED, 1, false, null, NOW, NOW, null, null, List.of(), NOW, "r", null);
    }

    @Test
    void stopAbortsCancelsOutboxAndWithdrawsPendingReview() {
        when(journeys.lock("j1")).thenReturn(Optional.of(row(JourneyStatus.ACTIVE)));
        JourneyReview pending =
                new JourneyReview("r1", "j1", "PUBLISH", ReviewStatus.PENDING, 1, 1, null, new UserRef("u1", "n"), NOW, null, null, null, null);
        when(reviews.findPending("j1")).thenReturn(Optional.of(pending));
        when(queries.detail("j1")).thenReturn(Optional.of(detail()));

        JourneyDetail d = service.stop("j1", new ReasonRequest("取消"), new Caller("u2", false));

        assertThat(d.status()).isEqualTo(JourneyStatus.ABORTED);
        verify(journeys).markAborted("j1", "取消", "u2", NOW);
        verify(outbox).cancelPendingByJourney("j1");
        verify(reviews).decide("r1", ReviewStatus.WITHDRAWN, "u2", NOW, "旅程已停止", null);
    }

    @Test
    void stopAgainChangesNothing() {
        when(journeys.lock("j1")).thenReturn(Optional.of(row(JourneyStatus.ABORTED)));
        when(queries.detail("j1")).thenReturn(Optional.of(detail()));
        service.stop("j1", new ReasonRequest("第二次"), new Caller(null, true));
        verify(journeys, never()).markAborted(any(), any(), any(), any());
        verify(outbox, never()).cancelPendingByJourney(any());
    }

    @Test
    void stopRules() {
        Caller c = new Caller("u1", false);
        assertThatThrownBy(() -> service.stop("j1", null, c)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.stop("j1", new ReasonRequest(" "), c)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.stop("j1", new ReasonRequest("x".repeat(201)), c))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.stop("j1", new ReasonRequest("a\u0000"), c)).isInstanceOf(ApiException.class);

        when(journeys.lock("j1")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.stop("j1", new ReasonRequest("x"), c)).isInstanceOf(ApiException.class);

        when(journeys.lock("j1")).thenReturn(Optional.of(row(JourneyStatus.DRAFT)));
        assertThatThrownBy(() -> service.stop("j1", new ReasonRequest("x"), c))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.JOURNEY_NOT_PUBLISHED));
    }
}
