package com.cdp.profile;

import java.util.List;

/** profiles.md §3 {@code GET /profiles/search} 的回應。 */
public record ProfileSearchResponse(List<ProfileSummary> items) {}
