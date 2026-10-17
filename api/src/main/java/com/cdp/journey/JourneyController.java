package com.cdp.journey;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.cdp.common.CdpProperties;

/** 契約 §3.1。 */
@RestController
@RequestMapping("/api/v1/journeys")
public class JourneyController {

    private final JourneyService service;
    private final CdpProperties properties;

    public JourneyController(JourneyService service, CdpProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public JourneyCreatedResponse create(@RequestBody CreateJourneyRequest request) {
        return service.create(properties.tenantId(), request);
    }
}
