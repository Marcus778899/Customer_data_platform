package com.cdp.journey;

import java.util.Map;

/** 旅程定義中的節點（03 §4）。outputs 值為 String；僅 StartNode 允許 List<String>。 */
public record JourneyNodeDefinition(
        String id, String type, String name, Map<String, Object> config, Map<String, Object> outputs) {}
