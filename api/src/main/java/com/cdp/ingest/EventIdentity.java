package com.cdp.ingest;

import java.util.List;

/** 01 §3 identity。v0.1.0 無身分解析，profileId 必填（契約 §3.2）。 */
public record EventIdentity(String profileId, List<Identifier> identifiers) {}
