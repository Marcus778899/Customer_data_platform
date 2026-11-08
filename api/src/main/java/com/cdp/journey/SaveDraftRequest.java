package com.cdp.journey;

/** definition、layout 先收成任意 JSON，由服務層只驗結構（journeys.md §3 PUT）。 */
public record SaveDraftRequest(Integer revision, Object definition, Object layout) {}
