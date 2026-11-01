package com.cdp.journey;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.cdp.common.web.ApiException;
import com.cdp.common.web.ErrorCode;
import com.cdp.common.web.FieldError;
import com.cdp.query.Page;
import com.cdp.query.PageCursor;

/** journeys.md §2：列表（篩選、游標分頁）、旅程詳情、版本內容。 */
@Service
public class JourneyQueryService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final JourneyQueryStore store;
    private final JourneyDefinitionStore definitions;

    public JourneyQueryService(JourneyQueryStore store, JourneyDefinitionStore definitions) {
        this.store = store;
        this.definitions = definitions;
    }

    public Page<JourneySummary> list(String status, String q, String cursor, Integer limit) {
        Set<JourneyStatus> statuses = parseStatuses(status);
        if (q != null && q.indexOf((char) 0) >= 0) {
            throw invalid("q", "不可含 NUL 字元");
        }
        int size = DEFAULT_LIMIT;
        if (limit != null) {
            if (limit < 1 || limit > MAX_LIMIT) {
                throw invalid("limit", "須介於 1 到 100");
            }
            size = limit;
        }
        PageCursor after = cursor == null ? null : PageCursor.decode(cursor);
        String query = q == null || q.isBlank() ? null : q;
        List<JourneySummary> rows = store.list(query, statuses, after, size + 1);
        if (rows.size() <= size) {
            return new Page<>(rows, null);
        }
        List<JourneySummary> items = new ArrayList<>(rows.subList(0, size));
        JourneySummary last = items.get(size - 1);
        return new Page<>(items, new PageCursor(last.updatedAt(), last.journeyId()).encode());
    }

    public JourneyDetail detail(String journeyId) {
        return store.detail(journeyId).orElseThrow(JourneyQueryService::notFound);
    }

    public JourneyVersionView version(String journeyId, int version) {
        JourneyDefinition d =
                definitions.find(journeyId, version).orElseThrow(JourneyQueryService::notFound);
        return new JourneyVersionView(
                d.journeyId(),
                d.name(),
                d.version(),
                d.description(),
                d.settings(),
                d.entryNode(),
                d.nodes(),
                null,
                d.publishedAt());
    }

    private static Set<JourneyStatus> parseStatuses(String status) {
        Set<JourneyStatus> out = new LinkedHashSet<>();
        if (status == null || status.isBlank()) {
            return out;
        }
        for (String part : status.split(",")) {
            try {
                out.add(JourneyStatus.valueOf(part.strip()));
            } catch (IllegalArgumentException e) {
                throw invalid("status", "不是有效的旅程狀態");
            }
        }
        return out;
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.JOURNEY_NOT_FOUND, "找不到這個旅程或版本。");
    }

    private static ApiException invalid(String field, String message) {
        return new ApiException(
                ErrorCode.VALIDATION_FAILED, "請求參數不正確。", List.of(new FieldError(field, message)));
    }
}
