package com.cdp.ingest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cdp.common.web.ApiException;
import com.cdp.common.web.ErrorCode;

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
        IngestResult result = service.ingest(event);
        if (result.status() == IngestStatus.REJECTED) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "事件欄位驗證失敗。", result.errors());
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(result);
    }

    @PostMapping("/batch")
    public ResponseEntity<BatchIngestResponse> ingestBatch(@RequestBody BatchIngestRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.ingestBatch(request));
    }
}
