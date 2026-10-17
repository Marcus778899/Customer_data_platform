package com.cdp.ingest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 契約 §3.2。單筆 REJECTED → 400；ACCEPTED／DUPLICATE → 202。 */
@RestController
@RequestMapping("/api/v1/events")
public class EventController {

    private final EventIngestionService service;

    public EventController(EventIngestionService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<IngestResult> ingest(@RequestBody EventRequest event) {
        throw new UnsupportedOperationException("TODO(BE-01)");
    }

    @PostMapping("/batch")
    public ResponseEntity<BatchIngestResponse> ingestBatch(@RequestBody BatchIngestRequest request) {
        throw new UnsupportedOperationException("TODO(BE-01)");
    }
}
