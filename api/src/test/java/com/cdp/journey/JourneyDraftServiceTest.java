package com.cdp.journey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.cdp.common.web.ApiException;
import com.cdp.common.web.ErrorCode;
import com.cdp.journey.JourneyStore.JourneyRow;

import tools.jackson.databind.json.JsonMapper;

class JourneyDraftServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-08T01:00:00Z");

    private final JourneyStore journeys = mock(JourneyStore.class);
    private final JourneyReviewStore reviews = mock(JourneyReviewStore.class);
    private final UserRefs users = mock(UserRefs.class);
    private final JourneyDraftService service =
            new JourneyDraftService(
                    journeys,
                    reviews,
                    new JourneyGraphValidator(),
                    users,
                    () -> "01JOURNEYID",
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    JsonMapper.builder().build());

    private static JourneyRow row(JourneyStatus status, Map<String, Object> draft, int revision) {
        return new JourneyRow(
                "j1", status, status == JourneyStatus.DRAFT ? null : 1, draft, null, revision, "u1", NOW, NOW, NOW, null, null, null);
    }

    private static ErrorCode codeOf(Throwable t) {
        return ((ApiException) t).code();
    }

    @Test
    void createStoresSkeleton() {
        DraftCreatedResponse r = service.create(new CreateDraftRequest("新旅程"), new Caller("u1", false));
        assertThat(r.journeyId()).isEqualTo("01JOURNEYID");
        assertThat(r.status()).isEqualTo(JourneyStatus.DRAFT);
        assertThat(r.revision()).isEqualTo(1);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> draft = ArgumentCaptor.forClass(Map.class);
        verify(journeys).insertDraftJourney(eq("01JOURNEYID"), draft.capture(), eq("u1"), eq(NOW));
        assertThat(draft.getValue()).containsEntry("name", "新旅程").containsEntry("entry_node", "start");
        assertThat((List<?>) draft.getValue().get("nodes")).hasSize(1);
    }

    @Test
    void createValidatesName() {
        Caller c = new Caller("u1", false);
        assertThatThrownBy(() -> service.create(null, c)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.create(new CreateDraftRequest(" "), c)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.create(new CreateDraftRequest("x".repeat(101)), c))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.create(new CreateDraftRequest("a\u0000"), c)).isInstanceOf(ApiException.class);
        verify(journeys, never()).insertDraftJourney(any(), any(), any(), any());
    }

    @Test
    void saveChecksStructureBeforeTouchingDatabase() {
        Caller c = new Caller("u1", false);
        assertThatThrownBy(() -> service.save("j1", null, c)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.save("j1", new SaveDraftRequest(null, Map.of(), null), c))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.save("j1", new SaveDraftRequest(1, "text", null), c))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.save("j1", new SaveDraftRequest(1, Map.of("nodes", "x"), null), c))
                .isInstanceOf(ApiException.class);
        List<Object> many = new ArrayList<>();
        for (int i = 0; i < 201; i++) {
            many.add(Map.of("id", "n" + i));
        }
        assertThatThrownBy(() -> service.save("j1", new SaveDraftRequest(1, Map.of("nodes", many), null), c))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.save("j1", new SaveDraftRequest(1, Map.of("nodes", List.of()), "x"), c))
                .isInstanceOf(ApiException.class);
        Map<String, Object> huge = Map.of("nodes", List.of(), "settings", Map.of("junk", "x".repeat(300 * 1024)));
        assertThatThrownBy(() -> service.save("j1", new SaveDraftRequest(1, huge, null), c))
                .isInstanceOf(ApiException.class);
        verify(journeys, never()).lock(any());
    }

    @Test
    void saveIncrementsRevision() {
        Map<String, Object> def = Map.of("name", "n", "nodes", List.of());
        when(journeys.lock("j1")).thenReturn(Optional.of(row(JourneyStatus.DRAFT, Map.of(), 2)));
        when(journeys.find("j1")).thenReturn(Optional.of(row(JourneyStatus.DRAFT, def, 3)));
        when(reviews.findPending("j1")).thenReturn(Optional.empty());
        when(reviews.latest("j1")).thenReturn(Optional.empty());

        Draft d = service.save("j1", new SaveDraftRequest(2, def, Map.of("nodes", Map.of())), new Caller("u1", false));

        assertThat(d.revision()).isEqualTo(3);
        verify(journeys).saveDraft(eq("j1"), eq(3), eq(def), eq(Map.of("nodes", Map.of())), eq("u1"), eq(NOW));
    }

    @Test
    void saveStateGuards() {
        Caller c = new Caller("u1", false);
        SaveDraftRequest req = new SaveDraftRequest(0, Map.of("nodes", List.of()), null);
        when(journeys.lock("j1")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.save("j1", req, c)).isInstanceOf(ApiException.class);

        when(journeys.lock("j1")).thenReturn(Optional.of(row(JourneyStatus.ABORTED, null, 0)));
        assertThatThrownBy(() -> service.save("j1", req, c))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.JOURNEY_ABORTED));

        when(journeys.lock("j1")).thenReturn(Optional.of(row(JourneyStatus.ACTIVE, null, 0)));
        when(reviews.findPending("j1")).thenReturn(Optional.of(mock(JourneyReview.class)));
        assertThatThrownBy(() -> service.save("j1", req, c))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.DRAFT_IN_REVIEW));

        when(reviews.findPending("j1")).thenReturn(Optional.empty());
        when(journeys.lock("j1")).thenReturn(Optional.of(row(JourneyStatus.ACTIVE, Map.of(), 4)));
        assertThatThrownBy(() -> service.save("j1", req, c))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.DRAFT_CONFLICT));
    }

    @Test
    void getAndValidate() {
        when(journeys.find("j1")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get("j1")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.validate("j1")).isInstanceOf(ApiException.class);

        when(journeys.find("j1")).thenReturn(Optional.of(row(JourneyStatus.ACTIVE, null, 0)));
        assertThatThrownBy(() -> service.get("j1"))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.DRAFT_NOT_FOUND));
        assertThatThrownBy(() -> service.validate("j1"))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.DRAFT_NOT_FOUND));

        Map<String, Object> def = Map.of("name", "n", "nodes", List.of());
        when(journeys.find("j1")).thenReturn(Optional.of(row(JourneyStatus.DRAFT, def, 2)));
        JourneyReview rejected =
                new JourneyReview("r", "j1", "PUBLISH", ReviewStatus.REJECTED, 2, null, null, null, NOW, null, NOW, "x", null);
        when(reviews.latest("j1")).thenReturn(Optional.of(rejected));
        when(users.of("u1")).thenReturn(new UserRef("u1", "名"));
        Draft d = service.get("j1");
        assertThat(d.latestReview()).isEqualTo(rejected);
        assertThat(d.updatedBy().displayName()).isEqualTo("名");
        assertThat(service.validate("j1").valid()).isFalse();

        JourneyReview approved =
                new JourneyReview("r", "j1", "PUBLISH", ReviewStatus.APPROVED, null, null, null, null, NOW, null, NOW, "x", 1);
        when(reviews.latest("j1")).thenReturn(Optional.of(approved));
        assertThat(service.get("j1").latestReview()).as("已核准的紀錄不算這份草稿的").isNull();
    }

    @Test
    void deleteRules() {
        when(journeys.lock("j1")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.delete("j1")).isInstanceOf(ApiException.class);

        when(journeys.lock("j1")).thenReturn(Optional.of(row(JourneyStatus.DRAFT, Map.of(), 1)));
        assertThatThrownBy(() -> service.delete("j1"))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.DRAFT_REQUIRED));

        when(journeys.lock("j1")).thenReturn(Optional.of(row(JourneyStatus.ACTIVE, Map.of(), 1)));
        when(reviews.findPending("j1")).thenReturn(Optional.of(mock(JourneyReview.class)));
        assertThatThrownBy(() -> service.delete("j1"))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.DRAFT_IN_REVIEW));

        when(reviews.findPending("j1")).thenReturn(Optional.empty());
        service.delete("j1");
        verify(journeys).clearDraft("j1", NOW);

        when(journeys.lock("j1")).thenReturn(Optional.of(row(JourneyStatus.ACTIVE, null, 0)));
        service.delete("j1");
        verify(journeys, org.mockito.Mockito.times(1)).clearDraft(eq("j1"), any());
        verify(journeys, never()).saveDraft(any(), org.mockito.ArgumentMatchers.anyInt(), any(), isNull(), any(), any());
    }
}
