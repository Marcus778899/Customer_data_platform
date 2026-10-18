package com.cdp.ingest;

import java.util.List;

public record BatchIngestRequest(List<EventRequest> events) {}
