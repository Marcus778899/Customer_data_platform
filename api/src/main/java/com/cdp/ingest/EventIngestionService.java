package com.cdp.ingest;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.cdp.common.web.ApiException;
import com.cdp.common.web.ErrorCode;
import com.cdp.common.web.FieldError;
import com.cdp.profile.ProfileWriter;

/** 驗證 → 落地（去重）；不同步建立 Enrollment，由 EventDispatcher 非同步扇出。 */
@Service
public class EventIngestionService {

    static final int MAX_BATCH = 500;

    private final EventValidator validator;
    private final EventStore store;
    private final Clock clock;
    private final ProfileWriter profiles;

    public EventIngestionService(
            EventValidator validator, EventStore store, Clock clock, ProfileWriter profiles) {
        this.validator = validator;
        this.store = store;
        this.clock = clock;
        this.profiles = profiles;
    }

    public IngestResult ingest(EventRequest event) {
        Instant receivedAt = clock.instant();
        List<FieldError> errors = validator.validate(event, receivedAt);
        String eventId = event == null ? null : event.eventId();
        if (!errors.isEmpty()) {
            return new IngestResult(eventId, IngestStatus.REJECTED, errors);
        }
        NormalizedEvent normalized =
                new NormalizedEvent(
                        event.eventId(),
                        event.sourceType(),
                        event.sourceId(),
                        event.name(),
                        event.occurredAt(),
                        receivedAt,
                        event.identity(),
                        event.properties(),
                        event.context());
        // profiles.md §2 末條：未知客戶同步建空白客戶；放在寫入事件之前，重送時也能補建
        profiles.ensureExists(event.identity().profileId());
        boolean inserted = store.insertIfAbsent(normalized);
        return new IngestResult(
                eventId, inserted ? IngestStatus.ACCEPTED : IngestStatus.DUPLICATE, List.of());
    }

    /** 上限 500 筆，超過丟 ApiException(BATCH_TOO_LARGE)；逐筆結果，部分成功不整批失敗。 */
    public BatchIngestResponse ingestBatch(BatchIngestRequest request) {
        List<EventRequest> events = request == null ? null : request.events();
        if (events == null || events.isEmpty()) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "events 須有 1 到 " + MAX_BATCH + " 筆。",
                    List.of(new FieldError("events", "須有 1 到 " + MAX_BATCH + " 筆")));
        }
        if (events.size() > MAX_BATCH) {
            throw new ApiException(
                    ErrorCode.BATCH_TOO_LARGE,
                    "單次最多 " + MAX_BATCH + " 筆。",
                    List.of(new FieldError("events", "最多 " + MAX_BATCH + " 筆")));
        }
        List<IngestResult> results = new ArrayList<>(events.size());
        int accepted = 0;
        int rejected = 0;
        for (int i = 0; i < events.size(); i++) {
            IngestResult single = ingest(events.get(i));
            if (single.status() == IngestStatus.REJECTED) {
                rejected++;
                String prefix = "events[" + i + "].";
                single =
                        new IngestResult(
                                single.eventId(),
                                single.status(),
                                single.errors().stream()
                                        .map(e -> new FieldError(prefix + e.field(), e.message()))
                                        .toList());
            } else {
                accepted++;
            }
            results.add(single);
        }
        return new BatchIngestResponse(accepted, rejected, results);
    }
}
