package com.cdp.journey;

import java.util.List;
import java.util.Optional;

/** 旅程定義存取。只增不改：新版本 = 新列（03 §1）。 */
public interface JourneyDefinitionStore {

    /** 寫入新版本；version 由實作取 max+1（同 journey_id 併發時以 PK 擋下重試）。 */
    JourneyDefinition insertNewVersion(JourneyDefinition definition);

    Optional<JourneyDefinition> find(String tenantId, String journeyId, int version);

    /** 每個 journey_id 只取最新且 ACTIVE 的版本。 */
    List<EventSourceBinding> findActiveEventSources(String tenantId, String eventName);
}
