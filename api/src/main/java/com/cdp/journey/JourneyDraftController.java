package com.cdp.journey;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.cdp.query.Page;

/** journeys.md §3 草稿與 §3.2 審核。角色由 SecurityConfig 控管（送審類只收 session）。 */
@RestController
@RequestMapping("/api/v1/journeys")
public class JourneyDraftController {

    private final JourneyDraftService drafts;
    private final JourneyReviewService reviews;

    public JourneyDraftController(JourneyDraftService drafts, JourneyReviewService reviews) {
        this.drafts = drafts;
        this.reviews = reviews;
    }

    @PostMapping("/drafts")
    @ResponseStatus(HttpStatus.CREATED)
    public DraftCreatedResponse create(@RequestBody(required = false) CreateDraftRequest request, Authentication auth) {
        return drafts.create(request, Caller.of(auth));
    }

    @GetMapping("/{journeyId}/draft")
    public Draft get(@PathVariable String journeyId) {
        return drafts.get(journeyId);
    }

    @PutMapping("/{journeyId}/draft")
    public Draft save(
            @PathVariable String journeyId, @RequestBody(required = false) SaveDraftRequest request, Authentication auth) {
        return drafts.save(journeyId, request, Caller.of(auth));
    }

    @DeleteMapping("/{journeyId}/draft")
    public ResponseEntity<Void> delete(@PathVariable String journeyId) {
        drafts.delete(journeyId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{journeyId}/draft/validate")
    public ValidationReport validate(@PathVariable String journeyId) {
        return drafts.validate(journeyId);
    }

    @PostMapping("/{journeyId}/draft/submit")
    @ResponseStatus(HttpStatus.CREATED)
    public JourneyReview submit(
            @PathVariable String journeyId, @RequestBody(required = false) SubmitRequest request, Authentication auth) {
        return reviews.submit(journeyId, request, Caller.of(auth));
    }

    @PostMapping("/{journeyId}/reviews/{reviewId}/approve")
    public JourneyReview approve(
            @PathVariable String journeyId,
            @PathVariable String reviewId,
            @RequestBody(required = false) ReasonRequest request,
            Authentication auth) {
        return reviews.approve(journeyId, reviewId, request, Caller.of(auth));
    }

    @PostMapping("/{journeyId}/reviews/{reviewId}/reject")
    public JourneyReview reject(
            @PathVariable String journeyId,
            @PathVariable String reviewId,
            @RequestBody(required = false) ReasonRequest request,
            Authentication auth) {
        return reviews.reject(journeyId, reviewId, request, Caller.of(auth));
    }

    @PostMapping("/{journeyId}/reviews/{reviewId}/withdraw")
    public JourneyReview withdraw(
            @PathVariable String journeyId,
            @PathVariable String reviewId,
            @RequestBody(required = false) ReasonRequest request,
            Authentication auth) {
        return reviews.withdraw(journeyId, reviewId, request, Caller.of(auth));
    }

    @GetMapping("/{journeyId}/reviews")
    public Page<JourneyReview> reviews(
            @PathVariable String journeyId,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String cursor,
            @org.springframework.web.bind.annotation.RequestParam(required = false) Integer limit) {
        return reviews.list(journeyId, cursor, limit);
    }
}
