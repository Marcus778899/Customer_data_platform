package com.cdp.journey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.cdp.common.web.ApiException;
import com.cdp.common.web.ErrorCode;

class ReviewPolicyTest {

    private static JourneyReview pendingBy(String userId) {
        return new JourneyReview(
                "r1",
                "j1",
                "PUBLISH",
                ReviewStatus.PENDING,
                2,
                null,
                null,
                new UserRef(userId, "n"),
                Instant.EPOCH,
                null,
                null,
                null,
                null);
    }

    @Test
    void selfApprovalIsForbidden_othersMayApprove() {
        ReviewPolicy policy = new ReviewPolicy(true);
        assertThatThrownBy(() -> policy.checkApprove(pendingBy("u1"), new Caller("u1", true)))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.SELF_APPROVAL_FORBIDDEN));
        assertThatCode(() -> policy.checkApprove(pendingBy("u1"), new Caller("u2", true))).doesNotThrowAnyException();
        assertThatCode(() -> policy.checkApprove(pendingBy("u1"), new Caller(null, true))).doesNotThrowAnyException();
    }

    @Test
    void withdrawOnlyBySubmitterOrAdmin() {
        ReviewPolicy policy = new ReviewPolicy(true);
        assertThatCode(() -> policy.checkWithdraw(pendingBy("u1"), new Caller("u1", false)))
                .doesNotThrowAnyException();
        assertThatCode(() -> policy.checkWithdraw(pendingBy("u1"), new Caller("u9", true)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.checkWithdraw(pendingBy("u1"), new Caller("u2", false)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void adminKeySwitch() {
        assertThatCode(() -> new ReviewPolicy(true).checkAdminKeyCreate()).doesNotThrowAnyException();
        assertThatThrownBy(() -> new ReviewPolicy(false).checkAdminKeyCreate())
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.ADMIN_KEY_JOURNEY_CREATE_DISABLED));
    }

    @Test
    void adminKeyRecordShape() {
        Instant now = Instant.parse("2026-11-08T00:00:00Z");
        JourneyReview r = new ReviewPolicy(true).adminKeyRecord("r1", "j1", 3, now);
        assertThat(r.status()).isEqualTo(ReviewStatus.APPROVED);
        assertThat(r.revision()).isNull();
        assertThat(r.submittedBy()).isNull();
        assertThat(r.decidedBy()).isNull();
        assertThat(r.reason()).isEqualTo("管理金鑰建立");
        assertThat(r.version()).isEqualTo(3);
        assertThat(r.submittedAt()).isEqualTo(now);
        assertThat(r.decidedAt()).isEqualTo(now);
        assertThat(r.brief().reviewId()).isEqualTo("r1");
    }
}
