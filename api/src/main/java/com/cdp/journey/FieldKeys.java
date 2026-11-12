package com.cdp.journey;

import java.time.Instant;
import java.util.List;

/** journeys.md §4 {@code field-keys} 回應。 */
public record FieldKeys(List<String> profileAttributes, List<String> eventProperties, Instant asOf) {}
