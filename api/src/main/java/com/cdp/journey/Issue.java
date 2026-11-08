package com.cdp.journey;

/** journeys.md §3 {@code Issue}：rule 為 spec 03 §6 規則編號（沒有編號的檢查為 null）。 */
public record Issue(String field, String message, Integer rule, String nodeId) {}
