package com.cdp.ingest;

import java.util.List;

import com.cdp.common.web.FieldError;

/** 單筆攝取結果（契約 §3.2）；REJECTED 時 errors 非空。 */
public record IngestResult(String eventId, IngestStatus status, List<FieldError> errors) {}
