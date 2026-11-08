package com.cdp.journey;

import java.util.List;

/** journeys.md §3 {@code ValidationReport}。 */
public record ValidationReport(boolean valid, List<Issue> errors, List<Issue> warnings) {}
