package com.cdp.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.cdp.journey.JourneyReview;
import com.cdp.journey.ReviewStatus;
import com.cdp.journey.UserRef;
import com.cdp.query.PageCursor;

/** 真的 PostgreSQL：journey_review 的新增、查詢、條件式轉終態、分頁與唯一約束。 */
class JdbcJourneyReviewStoreIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private JdbcClient jdbc;
    private JdbcJourneyReviewStore store;

    @BeforeEach
    void setUp() {
        jdbc = PgTestDb.fresh();
        store = new JdbcJourneyReviewStore(jdbc);
        jdbc.sql(
                        "insert into app_user (user_id, email, display_name, password_hash, role, status,"
                                + " created_at, updated_at) values ('u1', 'a@x.com', 'Alice', 'h', 'EDITOR',"
                                + " 'ACTIVE', now(), now()), ('u2', 'b@x.com', 'Bob', 'h', 'ADMIN', 'DISABLED',"
                                + " now(), now())")
                .update();
    }

    private static JourneyReview pending(String id, String journey, Instant at) {
        return new JourneyReview(
                id, journey, "PUBLISH", ReviewStatus.PENDING, 3, 1, "請審", new UserRef("u1", "Alice"), at, null,
                null, null, null);
    }

    @Test
    void insert_thenFindByIdAndPending_withUserNames() {
        store.insert(pending("r1", "j", T0));

        JourneyReview r = store.find("j", "r1").orElseThrow();
        assertThat(r.status()).isEqualTo(ReviewStatus.PENDING);
        assertThat(r.submittedBy()).isEqualTo(new UserRef("u1", "Alice"));
        assertThat(r.submittedAt()).isEqualTo(T0);
        assertThat(r.revision()).isEqualTo(3);
        assertThat(r.baseVersion()).isEqualTo(1);
        assertThat(r.note()).isEqualTo("請審");
        assertThat(r.decidedBy()).isNull();
        assertThat(r.version()).isNull();
        assertThat(store.findPending("j")).isPresent();
        assertThat(store.find("other", "r1")).isEmpty();
        assertThat(store.findPending("other")).isEmpty();
    }

    @Test
    void nullAndUnknownUsers_areHandled() {
        store.insert(new JourneyReview(
                "r1", "j", "PUBLISH", ReviewStatus.APPROVED, null, null, "管理金鑰建立", null, T0, null, T0, null, 1));
        store.insert(new JourneyReview(
                "r2", "j", "PUBLISH", ReviewStatus.WITHDRAWN, 1, 1, null, new UserRef("ghost", "ghost"),
                T0.plusSeconds(1), null, null, null, null));

        JourneyReview key = store.find("j", "r1").orElseThrow();
        assertThat(key.submittedBy()).isNull();
        assertThat(key.revision()).isNull();
        assertThat(key.version()).isEqualTo(1);
        assertThat(store.find("j", "r2").orElseThrow().submittedBy()).isEqualTo(new UserRef("ghost", "ghost"));
    }

    @Test
    void secondPendingForSameJourney_isDuplicateKey_otherJourneyAllowed() {
        store.insert(pending("r1", "j", T0));

        assertThatThrownBy(() -> store.insert(pending("r2", "j", T0.plusSeconds(1))))
                .isInstanceOf(DuplicateKeyException.class);
        store.insert(pending("r3", "other", T0));
    }

    @Test
    void decide_isConditionalOnPending_secondCallReturnsFalse() {
        store.insert(pending("r1", "j", T0));

        boolean first = store.decide("r1", ReviewStatus.APPROVED, "u2", T0.plusSeconds(5), null, 2);
        boolean second = store.decide("r1", ReviewStatus.REJECTED, "u2", T0.plusSeconds(6), "x", null);

        assertThat(first).isTrue();
        assertThat(second).isFalse();
        JourneyReview r = store.find("j", "r1").orElseThrow();
        assertThat(r.status()).isEqualTo(ReviewStatus.APPROVED);
        assertThat(r.decidedBy()).isEqualTo(new UserRef("u2", "Bob"));
        assertThat(r.decidedAt()).isEqualTo(T0.plusSeconds(5));
        assertThat(r.version()).isEqualTo(2);
        assertThat(store.findPending("j")).isEmpty();
        // 終態後同旅程可再送審
        store.insert(pending("r2", "j", T0.plusSeconds(10)));
    }

    @Test
    void reject_requiresReason() {
        store.insert(pending("r1", "j", T0));

        assertThatThrownBy(() -> store.decide("r1", ReviewStatus.REJECTED, "u2", T0, null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(store.decide("r1", ReviewStatus.REJECTED, "u2", T0, "不合格", null)).isTrue();
        assertThat(store.find("j", "r1").orElseThrow().reason()).isEqualTo("不合格");
    }

    @Test
    void latest_prefersPending_thenNewest() {
        assertThat(store.latest("j")).isEmpty();
        store.insert(pending("r1", "j", T0));
        store.decide("r1", ReviewStatus.WITHDRAWN, "u1", T0, null, null);
        store.insert(pending("r2", "j", T0.plusSeconds(10)));
        store.decide("r2", ReviewStatus.WITHDRAWN, "u1", T0, null, null);
        assertThat(store.latest("j").orElseThrow().reviewId()).isEqualTo("r2");

        store.insert(pending("r3", "j", T0.minusSeconds(100)));
        assertThat(store.latest("j").orElseThrow().reviewId()).isEqualTo("r3");
    }

    @Test
    void list_newestFirst_withKeysetPaging() {
        for (int i = 1; i <= 3; i++) {
            store.insert(pending("r" + i, "j", T0.plusSeconds(i)));
            store.decide("r" + i, ReviewStatus.WITHDRAWN, "u1", T0, null, null);
        }
        store.insert(pending("rx", "other", T0));

        List<JourneyReview> page1 = store.list("j", null, 2);
        assertThat(page1).extracting(JourneyReview::reviewId).containsExactly("r3", "r2");

        JourneyReview last = page1.get(1);
        List<JourneyReview> page2 = store.list("j", new PageCursor(last.submittedAt(), last.reviewId()), 2);
        assertThat(page2).extracting(JourneyReview::reviewId).containsExactly("r1");
    }
}
