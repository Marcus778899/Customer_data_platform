package com.cdp.profile;

import java.util.Set;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.cdp.engine.model.EnrollmentStatus;
import com.cdp.query.EnrollmentQueryService;
import com.cdp.query.EnrollmentSummary;
import com.cdp.query.Page;

/** profiles.md §2、§3。 */
@RestController
@RequestMapping("/api/v1/profiles")
public class ProfileController {

    private final ProfileService service;
    private final ProfileSearchService search;
    private final EnrollmentQueryService enrollments;

    public ProfileController(
            ProfileService service,
            ProfileSearchService search,
            EnrollmentQueryService enrollments) {
        this.service = service;
        this.search = search;
        this.enrollments = enrollments;
    }

    @PutMapping("/{profileId}")
    public ProfileDetail put(
            @PathVariable String profileId, @RequestBody ProfileWriteRequest request) {
        return service.put(profileId, request);
    }

    @GetMapping("/search")
    public ProfileSearchResponse search(@RequestParam String q) {
        return search.search(q);
    }

    @GetMapping("/{profileId}")
    public ProfileDetail detail(@PathVariable String profileId) {
        return service.detail(profileId);
    }

    @GetMapping("/{profileId}/enrollments")
    public Page<EnrollmentSummary> enrollments(
            @PathVariable String profileId,
            @RequestParam(required = false) Set<EnrollmentStatus> status,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        service.requireExists(profileId);
        return enrollments.listByProfile(profileId, status, cursor, limit);
    }
}
