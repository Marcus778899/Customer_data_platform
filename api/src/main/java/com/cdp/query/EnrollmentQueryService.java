package com.cdp.query;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import org.springframework.stereotype.Service;

import com.cdp.common.web.ApiException;
import com.cdp.common.web.ErrorCode;
import com.cdp.common.web.FieldError;
import com.cdp.engine.model.EnrollmentStatus;

/** 契約 §4 的分頁、預設值與 404 規則。 */
@Service
public class EnrollmentQueryService {

    private static final int DEFAULT_LIST_LIMIT = 20;
    private static final int DEFAULT_DELIVERY_LIMIT = 50;
    private static final int MAX_LIMIT = 100;
    private static final int HISTORY_SIZE = 50;

    private final EnrollmentQueryStore store;
    private final Clock clock;
    private final PiiMasker masker;

    public EnrollmentQueryService(EnrollmentQueryStore store, Clock clock, PiiMasker masker) {
        this.store = store;
        this.clock = clock;
        this.masker = masker;
    }

    public Page<EnrollmentSummary> list(
            String q, Set<EnrollmentStatus> statuses, String cursor, Integer limit) {
        if (q == null || q.isBlank()) {
            throw invalid("q", "必填");
        }
        rejectNul("q", q);
        if (statuses != null && statuses.contains(null)) {
            throw invalid("status", "格式錯誤");
        }
        int size = limit(limit, DEFAULT_LIST_LIMIT);
        PageCursor after = cursor == null ? null : PageCursor.decode(cursor);
        List<EnrollmentSummary> rows = store.list(q, statuses, after, size + 1);
        return page(rows, size, r -> new PageCursor(r.enrolledAt(), r.enrollmentId()));
    }

    /** 不存在丟 ApiException(ENROLLMENT_NOT_FOUND)。 */
    public EnrollmentDetail detail(String enrollmentId) {
        EnrollmentDetail raw = loadOrThrow(enrollmentId);
        List<DeliveryEntry> rows = store.deliveries(enrollmentId, null, HISTORY_SIZE + 1);
        Page<DeliveryEntry> history =
                page(rows, HISTORY_SIZE, d -> new PageCursor(d.attemptedAt(), d.idempotencyKey()));
        return masker.mask(
                new EnrollmentDetail(
                        raw.enrollmentId(),
                        raw.journeyId(),
                        raw.journeyVersion(),
                        raw.journeyName(),
                        raw.profileId(),
                        raw.status(),
                        raw.currentNodeId(),
                        raw.currentNodeName(),
                        raw.currentNodeType(),
                        raw.enrolledAt(),
                        raw.updatedAt(),
                        raw.terminatedAt(),
                        raw.terminationReason(),
                        raw.wakeCondition(),
                        raw.variables(),
                        raw.triggerSnapshot(),
                        raw.trace(),
                        history.items(),
                        history.nextCursor(),
                        clock.instant(),
                        List.of()));
    }

    public Page<DeliveryEntry> deliveries(
            String enrollmentId, String cursor, Integer limit) {
        int size = limit(limit, DEFAULT_DELIVERY_LIMIT);
        PageCursor after = cursor == null ? null : PageCursor.decode(cursor);
        loadOrThrow(enrollmentId);
        List<DeliveryEntry> rows = store.deliveries(enrollmentId, after, size + 1);
        return page(rows, size, d -> new PageCursor(d.attemptedAt(), d.idempotencyKey()));
    }

    private EnrollmentDetail loadOrThrow(String enrollmentId) {
        return store.detail(enrollmentId)
                .orElseThrow(
                        () -> new ApiException(ErrorCode.ENROLLMENT_NOT_FOUND, "找不到這筆 Enrollment。"));
    }

    private static void rejectNul(String field, String value) {
        if (value != null && value.indexOf((char) 0) >= 0) {
            throw invalid(field, "不可含 NUL 字元");
        }
    }

    private static int limit(Integer limit, int defaultValue) {
        if (limit == null) {
            return defaultValue;
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw invalid("limit", "須介於 1 到 100");
        }
        return limit;
    }

    private static <T> Page<T> page(List<T> rows, int size, Function<T, PageCursor> cursorOf) {
        if (rows.size() <= size) {
            return new Page<>(rows, null);
        }
        List<T> items = new ArrayList<>(rows.subList(0, size));
        return new Page<>(items, cursorOf.apply(items.get(size - 1)).encode());
    }

    private static ApiException invalid(String field, String message) {
        return new ApiException(
                ErrorCode.VALIDATION_FAILED, "請求參數不正確。", List.of(new FieldError(field, message)));
    }
}
