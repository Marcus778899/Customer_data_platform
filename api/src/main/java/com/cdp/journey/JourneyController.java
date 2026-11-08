package com.cdp.journey;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.cdp.query.Page;

/** journey-engine.md §3.1（建立）、journeys.md §2（唯讀）、§3.5（停止）、§4（field-keys）。 */
@RestController
@RequestMapping("/api/v1/journeys")
public class JourneyController {

    private final JourneyService service;
    private final JourneyQueryService queries;
    private final JourneyStopService stops;
    private final FieldKeysService fieldKeys;

    public JourneyController(
            JourneyService service,
            JourneyQueryService queries,
            JourneyStopService stops,
            FieldKeysService fieldKeys) {
        this.service = service;
        this.queries = queries;
        this.stops = stops;
        this.fieldKeys = fieldKeys;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public JourneyCreatedResponse create(@RequestBody CreateJourneyRequest request) {
        return service.create(request);
    }

    @GetMapping
    public Page<JourneySummary> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q,
            @RequestParam(name = "review_status", required = false) String reviewStatus,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return queries.list(status, q, reviewStatus, cursor, limit);
    }

    @GetMapping("/field-keys")
    public FieldKeys fieldKeys(@RequestParam(name = "event_name", required = false) String eventName) {
        return fieldKeys.fieldKeys(eventName);
    }

    @PostMapping("/{journeyId}/stop")
    public JourneyDetail stop(
            @PathVariable String journeyId, @RequestBody(required = false) ReasonRequest request, Authentication auth) {
        return stops.stop(journeyId, request, Caller.of(auth));
    }

    @GetMapping("/{journeyId}")
    public JourneyDetail detail(@PathVariable String journeyId) {
        return queries.detail(journeyId);
    }

    @GetMapping("/{journeyId}/versions/{version}")
    public JourneyVersionView version(@PathVariable String journeyId, @PathVariable int version) {
        return queries.version(journeyId, version);
    }
}
