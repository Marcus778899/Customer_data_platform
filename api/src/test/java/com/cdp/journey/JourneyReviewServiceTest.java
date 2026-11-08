package com.cdp.journey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import com.cdp.common.web.ApiException;
import com.cdp.common.web.ErrorCode;
import com.cdp.journey.JourneyStore.JourneyRow;
import com.cdp.query.Page;

class JourneyReviewServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-08T01:00:00Z");

    private final JourneyStore journeys = mock(JourneyStore.class);
    private final JourneyReviewStore reviews = mock(JourneyReviewStore.class);
    private final UserRefs users = mock(UserRefs.class);
    private final JourneyReviewService service =
            new JourneyReviewService(
                    journeys,
                    reviews,
                    new JourneyGraphValidator(),
                    new ReviewPolicy(true),
                    users,
                    () -> "01REVIEWID",
                    Clock.fixed(NOW, ZoneOffset.UTC));

    private static Map<String, Object> node(String id, String type, Map<String, Object> cfg, Map<String, Object> outs) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("id", id);
        n.put("type", type);
        n.put("name", null);
        n.put("config", cfg);
        n.put("outputs", outs);
        return n;
    }

    private static Map<String, Object> validDraft() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("name", "旅程");
        d.put("description", null);
        d.put("settings", Map.of());
        d.put("entry_node", "start");
        d.put(
                "nodes",
                new ArrayList<>(
                        List.of(
                                node("start", "StartNode", Map.of(), Map.of("next", "src_1")),
                                node("src_1", "EventSourceNode", Map.of("event_name", "e"), Map.of("next", "end_1")),
                                node("end_1", "EndNode", Map.of(), Map.of()))));
        return d;
    }

    private static JourneyRow row(JourneyStatus status, Map<String, Object> draft, int revision) {
        return new JourneyRow(
                "j1", status, status == JourneyStatus.DRAFT ? null : 1, draft, null, revision, "u1", NOW, NOW, NOW, null, null, null);
    }

    private static JourneyReview pending(String submitter) {
        return new JourneyReview(
                "r1", "j1", "PUBLISH", ReviewStatus.PENDING, 2, null, null, new UserRef(submitter, "n"), NOW, null, null, null, null);
    }

    private static JourneyReview done(ReviewStatus status) {
        return new JourneyReview("r1", "j1", "PUBLISH", status, 2, null, null, new UserRef("u1", "n"), NOW, null, NOW, null, null);
    }

    private static ErrorCode codeOf(Throwable t) {
        return ((ApiException) t).code();
    }

    @Test
    void submitCreatesPendingReview() {
        when(journeys.lock("j1")).thenReturn(Optional.of(row(JourneyStatus.DRAFT, validDraft(), 2)));
        when(reviews.findPending("j1")).thenReturn(Optional.empty());
        when(users.of("u1")).thenReturn(new UserRef("u1", "名"));

        JourneyReview r = service.submit("j1", new SubmitRequest(2, "請審"), new Caller("u1", false));

        assertThat(r.status()).isEqualTo(ReviewStatus.PENDING);
        assertThat(r.reviewId()).isEqualTo("01REVIEWID");
        assertThat(r.revision()).isEqualTo(2);
        assertThat(r.note()).isEqualTo("請審");
        assertThat(r.submittedAt()).isEqualTo(NOW);
        verify(reviews).insert(r);
    }

    @Test
    void submitRejectsBadInputAndStates() {
        Caller c = new Caller("u1", false);
        assertThatThrownBy(() -> service.submit("j1", null, c)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.submit("j1", new SubmitRequest(0, null), c)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.submit("j1", new SubmitRequest(1, "x".repeat(201)), c))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.submit("j1", new SubmitRequest(1, null), c)).isInstanceOf(ApiException.class);

        when(journeys.lock("j1")).thenReturn(Optional.of(row(JourneyStatus.ABORTED, validDraft(), 1)));
        assertThatThrownBy(() -> service.submit("j1", new SubmitRequest(1, null), c))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.JOURNEY_ABORTED));

        when(journeys.lock("j1")).thenReturn(Optional.of(row(JourneyStatus.ACTIVE, null, 0)));
        assertThatThrownBy(() -> service.submit("j1", new SubmitRequest(1, null), c))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.DRAFT_NOT_FOUND));

        when(journeys.lock("j1")).thenReturn(Optional.of(row(JourneyStatus.ACTIVE, validDraft(), 3)));
        assertThatThrownBy(() -> service.submit("j1", new SubmitRequest(1, null), c))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.DRAFT_CONFLICT));

        when(reviews.findPending("j1")).thenReturn(Optional.of(pending("u1")));
        assertThatThrownBy(() -> service.submit("j1", new SubmitRequest(3, null), c))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.REVIEW_PENDING));

        when(reviews.findPending("j1")).thenReturn(Optional.empty());
        doThrow(new DuplicateKeyException("dup")).when(reviews).insert(any());
        assertThatThrownBy(() -> service.submit("j1", new SubmitRequest(3, null), c))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.REVIEW_PENDING));
    }

    @Test
    void submitInvalidDraftIsValidationFailed() {
        Map<String, Object> bad = validDraft();
        bad.put("nodes", List.of(node("start", "StartNode", Map.of(), Map.of())));
        when(journeys.lock("j1")).thenReturn(Optional.of(row(JourneyStatus.DRAFT, bad, 1)));
        when(reviews.findPending("j1")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.submit("j1", new SubmitRequest(1, null), new Caller("u1", false)))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> {
                            assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                            assertThat(e.errors()).isNotEmpty();
                        });
        verify(reviews, never()).insert(any());
    }

    @Test
    void approveCreatesVersionAndPublishes() {
        when(journeys.lock("j1")).thenReturn(Optional.of(row(JourneyStatus.DRAFT, validDraft(), 2)));
        when(reviews.find("j1", "r1")).thenReturn(Optional.of(pending("u1")));
        when(journeys.insertVersion(eq("j1"), any(), any(), eq(NOW))).thenReturn(1);
        when(reviews.decide("r1", ReviewStatus.APPROVED, "admin", NOW, "OK", 1)).thenReturn(true);

        service.approve("j1", "r1", new ReasonRequest("OK"), new Caller("admin", true));

        verify(journeys).markPublished("j1", 1, true, NOW);
    }

    @Test
    void approveGuards() {
        Caller admin = new Caller("admin", true);
        when(journeys.lock("j1")).thenReturn(Optional.of(row(JourneyStatus.DRAFT, validDraft(), 2)));
        when(reviews.find("j1", "r1")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.approve("j1", "r1", null, admin))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.REVIEW_NOT_FOUND));

        when(reviews.find("j1", "r1")).thenReturn(Optional.of(done(ReviewStatus.APPROVED)));
        assertThatThrownBy(() -> service.approve("j1", "r1", null, admin))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.REVIEW_NOT_PENDING));

        when(reviews.find("j1", "r1")).thenReturn(Optional.of(pending("admin")));
        assertThatThrownBy(() -> service.approve("j1", "r1", null, admin))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.SELF_APPROVAL_FORBIDDEN));

        when(reviews.find("j1", "r1")).thenReturn(Optional.of(pending("u1")));
        when(journeys.lock("j1")).thenReturn(Optional.of(row(JourneyStatus.DRAFT, validDraft(), 9)));
        assertThatThrownBy(() -> service.approve("j1", "r1", null, admin))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.DRAFT_CONFLICT));

        Map<String, Object> bad = validDraft();
        bad.put("entry_node", "nope");
        when(journeys.lock("j1")).thenReturn(Optional.of(row(JourneyStatus.DRAFT, bad, 2)));
        assertThatThrownBy(() -> service.approve("j1", "r1", null, admin))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.VALIDATION_FAILED));

        when(journeys.lock("j1")).thenReturn(Optional.of(row(JourneyStatus.DRAFT, validDraft(), 2)));
        when(journeys.insertVersion(eq("j1"), any(), any(), eq(NOW))).thenReturn(1);
        when(reviews.decide(eq("r1"), eq(ReviewStatus.APPROVED), any(), any(), any(), anyInt())).thenReturn(false);
        assertThatThrownBy(() -> service.approve("j1", "r1", null, admin))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.REVIEW_NOT_PENDING));

        assertThatThrownBy(() -> service.approve("j1", "r1", new ReasonRequest("x".repeat(201)), admin))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void rejectNeedsReason() {
        Caller admin = new Caller("admin", true);
        assertThatThrownBy(() -> service.reject("j1", "r1", null, admin)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.reject("j1", "r1", new ReasonRequest(" "), admin)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.reject("j1", "r1", new ReasonRequest("x".repeat(201)), admin))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.reject("j1", "r1", new ReasonRequest("a\u0000b"), admin))
                .isInstanceOf(ApiException.class);

        when(journeys.lock("j1")).thenReturn(Optional.of(row(JourneyStatus.DRAFT, validDraft(), 2)));
        when(reviews.find("j1", "r1")).thenReturn(Optional.of(pending("u1")));
        when(reviews.decide("r1", ReviewStatus.REJECTED, "admin", NOW, "太短", null)).thenReturn(true);
        service.reject("j1", "r1", new ReasonRequest("太短"), admin);
        verify(reviews).decide("r1", ReviewStatus.REJECTED, "admin", NOW, "太短", null);
    }

    @Test
    void withdrawBySubmitterAdminOnly() {
        when(journeys.lock("j1")).thenReturn(Optional.of(row(JourneyStatus.DRAFT, validDraft(), 2)));
        when(reviews.find("j1", "r1")).thenReturn(Optional.of(pending("u1")));
        assertThatThrownBy(() -> service.withdraw("j1", "r1", null, new Caller("u2", false)))
                .isInstanceOf(ApiException.class);

        when(reviews.decide("r1", ReviewStatus.WITHDRAWN, "u1", NOW, null, null)).thenReturn(true);
        service.withdraw("j1", "r1", new ReasonRequest(" "), new Caller("u1", false));
        verify(reviews).decide("r1", ReviewStatus.WITHDRAWN, "u1", NOW, null, null);

        when(reviews.decide(eq("r1"), eq(ReviewStatus.WITHDRAWN), eq("u9"), any(), isNull(), isNull())).thenReturn(false);
        assertThatThrownBy(() -> service.withdraw("j1", "r1", null, new Caller("u9", true)))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.REVIEW_NOT_PENDING));
    }

    @Test
    void listPagesWithCursor() {
        when(journeys.find("j1")).thenReturn(Optional.of(row(JourneyStatus.ACTIVE, null, 0)));
        JourneyReview a = done(ReviewStatus.WITHDRAWN);
        when(reviews.list(eq("j1"), isNull(), eq(2))).thenReturn(List.of(a, a));
        Page<JourneyReview> page = service.list("j1", null, 1);
        assertThat(page.items()).hasSize(1);
        assertThat(page.nextCursor()).isNotBlank();

        Page<JourneyReview> last = service.list("j1", null, 5);
        assertThat(last.nextCursor()).isNull();

        assertThatThrownBy(() -> service.list("j1", null, 0)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.list("j1", "###", null)).isInstanceOf(ApiException.class);
        when(journeys.find("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.list("nope", null, null)).isInstanceOf(ApiException.class);
    }
}
