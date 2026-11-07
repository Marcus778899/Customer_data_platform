package com.cdp.nodestats;

import java.util.List;
import java.util.Optional;

/** 節點統計的資料存取；查詢逾時由實作負責。 */
public interface NodeStatsStore {

    /** 該版本 nodes 陣列順序的節點 id（不含 StartNode）；旅程或版本不存在回 empty。 */
    Optional<List<String>> nodeIds(String journeyId, int version);

    /** 依 nodeIds 順序、每個節點一筆（沒人經過的為 0）。 */
    List<NodeStat> stats(String journeyId, int version, List<String> nodeIds);
}
