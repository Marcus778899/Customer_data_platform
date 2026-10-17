package com.cdp.ingest;

import java.util.List;

/** accepted 含 DUPLICATE（01 §9：重複不是錯誤）。 */
public record BatchIngestResponse(int accepted, int rejected, List<IngestResult> results) {}
