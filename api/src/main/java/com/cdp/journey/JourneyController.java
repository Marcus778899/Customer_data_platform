package com.cdp.journey;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.cdp.query.Page;

/** journey-engine.md §3.1（建立）與 journeys.md §2（唯讀）。 */
@RestController
@RequestMapping("/api/v1/journeys")
public class JourneyController {

    private final JourneyService service;
    private final JourneyQueryService queries;

    public JourneyController(JourneyService service, JourneyQueryService queries) {
        this.service = service;
        this.queries = queries;
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
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return queries.list(status, q, cursor, limit);
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
