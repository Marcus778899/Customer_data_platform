package com.cdp.nodestats;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** journeys.md §5：旅程版本的節點統計。 */
@RestController
@RequestMapping("/api/v1/journeys")
public class NodeStatsController {

    private final NodeStatsService service;

    public NodeStatsController(NodeStatsService service) {
        this.service = service;
    }

    @GetMapping("/{journeyId}/versions/{version}/node-stats")
    public NodeStats nodeStats(@PathVariable String journeyId, @PathVariable int version) {
        return service.stats(journeyId, version);
    }
}
