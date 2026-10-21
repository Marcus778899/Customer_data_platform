package com.cdp.query;

import java.util.Set;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.cdp.engine.model.EnrollmentStatus;

/** 契約 §4（唯讀，D-005）。 */
@RestController
@RequestMapping("/api/v1/enrollments")
public class EnrollmentQueryController {

    private final EnrollmentQueryService service;

    public EnrollmentQueryController(EnrollmentQueryService service) {
        this.service = service;
    }

    @GetMapping
    public Page<EnrollmentSummary> list(
            @RequestParam String q,
            @RequestParam(required = false) Set<EnrollmentStatus> status,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return service.list(q, status, cursor, limit);
    }

    @GetMapping("/{enrollmentId}")
    public EnrollmentDetail detail(@PathVariable String enrollmentId) {
        return service.detail(enrollmentId);
    }

    @GetMapping("/{enrollmentId}/deliveries")
    public Page<DeliveryEntry> deliveries(
            @PathVariable String enrollmentId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return service.deliveries(enrollmentId, cursor, limit);
    }
}
