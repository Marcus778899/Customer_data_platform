package com.cdp.journey;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cdp.common.web.ApiException;
import com.cdp.common.web.ErrorCode;
import com.cdp.delivery.OutboxStore;
import com.cdp.journey.JourneyStore.JourneyRow;

/**
 * journeys.md §3.5 停止（spec 05 §5 旅程層）。同一交易：旅程設 ABORTED（新事件不再進入）、未推送的 outbox 列取消、
 * 送審中的審核轉 WITHDRAWN；進行中的 Enrollment 由 JourneyStopSweeper 背景轉 ABORTED，已推送未送出的由 consumer 取消。
 */
@Service
public class JourneyStopService {

    private final JourneyStore journeys;
    private final JourneyReviewStore reviews;
    private final OutboxStore outbox;
    private final JourneyQueryStore queries;
    private final Clock clock;

    public JourneyStopService(
            JourneyStore journeys,
            JourneyReviewStore reviews,
            OutboxStore outbox,
            JourneyQueryStore queries,
            Clock clock) {
        this.journeys = journeys;
        this.reviews = reviews;
        this.outbox = outbox;
        this.queries = queries;
        this.clock = clock;
    }

    @Transactional
    public JourneyDetail stop(String journeyId, ReasonRequest request, Caller caller) {
        String reason = request == null ? null : request.reason();
        if (reason == null || reason.isBlank()) {
            throw JourneyDraftService.invalid("reason", "必填");
        }
        if (reason.length() > JourneyReviewService.MAX_TEXT) {
            throw JourneyDraftService.invalid("reason", "不可超過 " + JourneyReviewService.MAX_TEXT + " 字");
        }
        if (reason.indexOf((char) 0) >= 0) {
            throw JourneyDraftService.invalid("reason", "不可含 NUL 字元");
        }
        JourneyRow row = journeys.lock(journeyId).orElseThrow(JourneyDraftService::journeyNotFound);
        if (row.status() == JourneyStatus.DRAFT) {
            throw new ApiException(ErrorCode.JOURNEY_NOT_PUBLISHED, "尚未發佈的旅程不能停止。");
        }
        if (row.status() != JourneyStatus.ABORTED) {
            Instant now = clock.instant();
            journeys.markAborted(journeyId, reason, caller.userId(), now);
            outbox.cancelPendingByJourney(journeyId);
            reviews.findPending(journeyId)
                    .ifPresent(
                            r ->
                                    reviews.decide(
                                            r.reviewId(),
                                            ReviewStatus.WITHDRAWN,
                                            caller.userId(),
                                            now,
                                            ReviewPolicy.STOPPED_REASON,
                                            null));
        }
        return queries.detail(journeyId).orElseThrow();
    }
}
