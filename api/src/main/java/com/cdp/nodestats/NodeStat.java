package com.cdp.nodestats;

import java.util.Map;

/** journeys.md §5 NodeStat。 */
public record NodeStat(String nodeId, int entered, int visits, int current, Map<String, Integer> outPorts) {}
