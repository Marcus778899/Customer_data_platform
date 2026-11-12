package com.cdp.nodestats;

import java.time.Instant;
import java.util.List;

/** journeys.md §5 NodeStats。 */
public record NodeStats(String journeyId, int version, List<NodeStat> items, Instant asOf) {}
