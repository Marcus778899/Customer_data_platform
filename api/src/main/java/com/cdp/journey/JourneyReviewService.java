package com.cdp.journey;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cdp.common.IdGenerator;
import com.cdp.common.web.ApiException;
import com.cdp.common.web.ErrorCode;
import com.cdp.journey.JourneyStore.JourneyRow;
import com.cdp.query.Page;
import com.cdp.query.PageCursor;

/**
 * journeys.md §3.2：送審、核准、退回、撤回與審核紀錄。審核規則集中在 {@link ReviewPolicy}。
 * 每個動作先鎖 journey 列（兩位管理員同時處理時後到者看到已不是 PENDING），再以條件式 UPDATE 收尾。
 */
@Service
public class JourneyReviewService {

    static final int MAX_TEXT = 200;
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final JourneyStore journeys;
    private final JourneyReviewStore reviews;
    private final JourneyGraphValidator validator;
    private final ReviewPolicy policy;
    private final UserRefs users;
    private final IdGenerator ids;
    private final Clock clock;

    public JourneyReviewService(
            JourneyStore journeys,
            JourneyReviewStore reviews,
            JourneyGraphValidator validator,
            ReviewPolicy policy,
            UserRefs users,
            IdGenerator ids,
            Clock clock) {
        this.journeys = journeys;
        this.reviews = reviews;
        this.validator = validator;
        this.policy = policy;
        this.users = users;
        this.ids = ids;
        this.clock = clock;
    }

    @Transactional
    public JourneyReview submit(String journeyId, SubmitRequest request, Caller caller) {
        if (request == null || request.revision() == null || request.revision() < 1) {
            throw JourneyDraftService.invalid("revision", "必填，須為正整數");
        }
        checkLength("note", request.note(), false);
        JourneyRow row = journeys.lock(journeyId).orElseThrow(JourneyDraftService::journeyNotFound);
        if (row.status() == JourneyStatus.ABORTED) {
            throw JourneyDraftService.aborted();
        }
        if (row.draft() == null) {
            throw new ApiException(ErrorCode.DRAFT_NOT_FOUND, "這個旅程目前沒有草稿。");
        }
        if (request.revision() != row.draftRevision()) {
            throw new ApiException(
                    ErrorCode.DRAFT_CONFLICT, "草稿已被更新（目前版次 " + row.draftRevision() + "），請重新載入後再送審。");
        }
        if (reviews.findPending(journeyId).isPresent()) {
            throw pendingExists();
        }
        ValidationReport report = validator.validate(row.draft());
        if (!report.valid()) {
            throw validationFailed(report.errors());
        }
        JourneyReview review =
                new JourneyReview(
                        ids.newId(),
                        journeyId,
                        JourneyReview.PUBLISH,
                        ReviewStatus.PENDING,
                        row.draftRevision(),
                        row.latestVersion(),
                        request.note(),
                        users.of(caller.userId()),
                        clock.instant(),
                        null,
                        null,
                        null,
                        null);
        try {
            reviews.insert(review);
        } catch (DuplicateKeyException e) {
            throw pendingExists();
        }
        return review;
    }

    @Transactional
    public JourneyReview approve(String journeyId, String reviewId, ReasonRequest request, Caller caller) {
        String reason = request == null ? null : request.reason();
        checkLength("reason", reason, false);
        JourneyRow row = journeys.lock(journeyId).orElseThrow(JourneyReviewService::reviewNotFound);
        JourneyReview review = reviews.find(journeyId, reviewId).orElseThrow(JourneyReviewService::reviewNotFound);
        requirePending(review);
        policy.checkApprove(review, caller);
        if (row.draft() == null || row.draftRevision() != review.revision()) {
            // 送審期間草稿鎖定，正常不會發生；保險起見不核准內容不明的草稿
            throw new ApiException(ErrorCode.DRAFT_CONFLICT, "草稿內容與送審時不一致，請退回後重新送審。");
        }
        Map<String, Object> definition = validator.normalize(row.draft());
        ValidationReport report = validator.validate(definition);
        if (!report.valid()) {
            throw validationFailed(report.errors());
        }
        Instant now = clock.instant();
        int version = journeys.insertVersion(journeyId, definition, row.draftLayout(), now);
        if (!reviews.decide(reviewId, ReviewStatus.APPROVED, caller.userId(), now, blankToNull(reason), version)) {
            throw notPending();
        }
        journeys.markPublished(journeyId, version, true, now);
        return reviews.find(journeyId, reviewId).orElseThrow();
    }

    @Transactional
    public JourneyReview reject(String journeyId, String reviewId, ReasonRequest request, Caller caller) {
        String reason = request == null ? null : request.reason();
        if (reason == null || reason.isBlank()) {
            throw JourneyDraftService.invalid("reason", "必填");
        }
        checkLength("reason", reason, true);
        return finish(journeyId, reviewId, ReviewStatus.REJECTED, reason, caller, false);
    }

    @Transactional
    public JourneyReview withdraw(String journeyId, String reviewId, ReasonRequest request, Caller caller) {
        String reason = request == null ? null : request.reason();
        checkLength("reason", reason, false);
        return finish(journeyId, reviewId, ReviewStatus.WITHDRAWN, blankToNull(reason), caller, true);
    }

    @Transactional(readOnly = true)
    public Page<JourneyReview> list(String journeyId, String cursor, Integer limit) {
        int size = DEFAULT_LIMIT;
        if (limit != null) {
            if (limit < 1 || limit > MAX_LIMIT) {
                throw JourneyDraftService.invalid("limit", "須介於 1 到 100");
            }
            size = limit;
        }
        PageCursor after = cursor == null ? null : PageCursor.decode(cursor);
        if (journeys.find(journeyId).isEmpty()) {
            throw JourneyDraftService.journeyNotFound();
        }
        List<JourneyReview> rows = reviews.list(journeyId, after, size + 1);
        if (rows.size() <= size) {
            return new Page<>(rows, null);
        }
        List<JourneyReview> items = new ArrayList<>(rows.subList(0, size));
        JourneyReview last = items.get(size - 1);
        return new Page<>(items, new PageCursor(last.submittedAt(), last.reviewId()).encode());
    }

    // ---------- 內部 ----------

    private JourneyReview finish(
            String journeyId, String reviewId, ReviewStatus status, String reason, Caller caller, boolean withdraw) {
        journeys.lock(journeyId).orElseThrow(JourneyReviewService::reviewNotFound);
        JourneyReview review = reviews.find(journeyId, reviewId).orElseThrow(JourneyReviewService::reviewNotFound);
        requirePending(review);
        if (withdraw) {
            policy.checkWithdraw(review, caller);
        }
        if (!reviews.decide(reviewId, status, caller.userId(), clock.instant(), reason, null)) {
            throw notPending();
        }
        return reviews.find(journeyId, reviewId).orElseThrow();
    }

    private static void requirePending(JourneyReview review) {
        if (review.status() != ReviewStatus.PENDING) {
            throw notPending();
        }
    }

    private static void checkLength(String field, String value, boolean required) {
        if (value == null) {
            return;
        }
        if (value.length() > MAX_TEXT) {
            throw JourneyDraftService.invalid(field, "不可超過 " + MAX_TEXT + " 字");
        }
        if (required && value.isBlank()) {
            throw JourneyDraftService.invalid(field, "必填");
        }
        if (value.indexOf((char) 0) >= 0) {
            throw JourneyDraftService.invalid(field, "不可含 NUL 字元");
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static ApiException validationFailed(List<Issue> errors) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "旅程定義驗證失敗。", errors);
    }

    private static ApiException pendingExists() {
        return new ApiException(ErrorCode.REVIEW_PENDING, "這個旅程已經有送審中的審核。");
    }

    private static ApiException notPending() {
        return new ApiException(ErrorCode.REVIEW_NOT_PENDING, "這筆審核已處理過，請重新載入。");
    }

    private static ApiException reviewNotFound() {
        return new ApiException(ErrorCode.REVIEW_NOT_FOUND, "找不到這筆審核。");
    }
}
