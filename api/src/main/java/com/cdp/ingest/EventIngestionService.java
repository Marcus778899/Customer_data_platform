package com.cdp.ingest;

import java.time.Clock;

import org.springframework.stereotype.Service;

import com.cdp.common.CdpProperties;

/** 驗證 → 落地（去重）；不同步建立 Enrollment，由 EventDispatcher 非同步扇出。 */
@Service
public class EventIngestionService {

    private final EventValidator validator;
    private final EventStore store;
    private final CdpProperties properties;
    private final Clock clock;

    public EventIngestionService(
            EventValidator validator, EventStore store, CdpProperties properties, Clock clock) {
        this.validator = validator;
        this.store = store;
        this.properties = properties;
        this.clock = clock;
    }

    public IngestResult ingest(EventRequest event) {
        throw new UnsupportedOperationException("TODO(BE-01)");
    }

    /** 上限 500 筆，超過丟 ApiException(BATCH_TOO_LARGE)；逐筆結果，部分成功不整批失敗。 */
    public BatchIngestResponse ingestBatch(BatchIngestRequest request) {
        throw new UnsupportedOperationException("TODO(BE-01)");
    }
}
