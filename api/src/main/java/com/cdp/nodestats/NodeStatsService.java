package com.cdp.nodestats;

import java.time.Clock;
import java.util.List;

import org.springframework.stereotype.Service;

import com.cdp.common.web.ApiException;
import com.cdp.common.web.ErrorCode;
import com.cdp.common.web.FieldError;

/** journeys.md §5：即時計算，不快取。 */
@Service
public class NodeStatsService {

    private final NodeStatsStore store;
    private final Clock clock;

    public NodeStatsService(NodeStatsStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    public NodeStats stats(String journeyId, int version) {
        if (version < 1) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "參數格式錯誤。",
                    List.of(new FieldError("version", "須為正整數")));
        }
        List<String> ids =
                store.nodeIds(journeyId, version)
                        .orElseThrow(() -> new ApiException(ErrorCode.JOURNEY_NOT_FOUND, "找不到這個旅程或版本。"));
        List<NodeStat> items = ids.isEmpty() ? List.of() : store.stats(journeyId, version, ids);
        return new NodeStats(journeyId, version, items, clock.instant());
    }
}
