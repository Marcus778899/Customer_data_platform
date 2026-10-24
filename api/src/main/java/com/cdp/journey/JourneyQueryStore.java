package com.cdp.journey;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.cdp.query.PageCursor;

/** journeys.md §2 唯讀查詢。第一版由 journey_definition 每個 journey_id 的最新列推導。 */
public interface JourneyQueryStore {

    /** q 為名稱包含（不分大小寫，null = 不篩）；statuses 為空 = 不篩；依 updated_at、journey_id 新到舊。 */
    List<JourneySummary> list(String q, Set<JourneyStatus> statuses, PageCursor after, int limit);

    Optional<JourneyDetail> detail(String journeyId);
}
