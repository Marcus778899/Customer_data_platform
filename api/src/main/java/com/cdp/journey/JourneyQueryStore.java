package com.cdp.journey;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.cdp.query.PageCursor;

/** journeys.md §2 唯讀查詢（第二版讀 journey 表，推導 review_status 與 latest_review）。 */
public interface JourneyQueryStore {

    /**
     * q 為名稱包含（不分大小寫，null = 不篩）；statuses 為空 = 不篩；reviewStatus 為 null = 不篩（PENDING、REJECTED）；
     * 依 updated_at、journey_id 新到舊。
     */
    List<JourneySummary> list(
            String q, Set<JourneyStatus> statuses, String reviewStatus, PageCursor after, int limit);

    Optional<JourneyDetail> detail(String journeyId);
}
