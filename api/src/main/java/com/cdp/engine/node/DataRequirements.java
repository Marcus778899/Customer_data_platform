package com.cdp.engine.node;

import java.util.Set;

/** 執行前要預查的資料（04 §2）。behaviorQueries 於 v0.2.0 加入；v0.1.0 節點一律回 NONE。 */
public record DataRequirements(
        Set<String> profileFields, Set<String> segmentIds, boolean needsDeliveryHistory) {

    public static final DataRequirements NONE = new DataRequirements(Set.of(), Set.of(), false);
}
