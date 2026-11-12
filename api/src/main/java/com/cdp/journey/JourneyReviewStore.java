package com.cdp.journey;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.cdp.query.PageCursor;

/** 表 journey_review。只 INSERT 與 PENDING → 終態的條件式 UPDATE，不刪除（spec 07 §12）。 */
public interface JourneyReviewStore {

    /** 同一旅程已有 PENDING 時丟 {@link org.springframework.dao.DuplicateKeyException}。 */
    void insert(JourneyReview review);

    Optional<JourneyReview> find(String journeyId, String reviewId);

    Optional<JourneyReview> findPending(String journeyId);

    /** 該旅程最新一筆（submitted_at、review_id 新到舊）。 */
    Optional<JourneyReview> latest(String journeyId);

    /** 以「status 仍為 PENDING」為條件轉終態，回傳是否生效。 */
    boolean decide(
            String reviewId,
            ReviewStatus status,
            String decidedBy,
            Instant decidedAt,
            String reason,
            Integer version);

    /** 依 submitted_at、review_id 新到舊。 */
    List<JourneyReview> list(String journeyId, PageCursor after, int limit);
}
