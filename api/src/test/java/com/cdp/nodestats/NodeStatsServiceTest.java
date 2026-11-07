package com.cdp.nodestats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.cdp.common.web.ApiException;
import com.cdp.common.web.ErrorCode;

class NodeStatsServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-07T03:00:00Z"), ZoneOffset.UTC);

    private static NodeStatsStore store(Optional<List<String>> ids) {
        return new NodeStatsStore() {
            @Override
            public Optional<List<String>> nodeIds(String journeyId, int version) {
                return ids;
            }

            @Override
            public List<NodeStat> stats(String journeyId, int version, List<String> nodeIds) {
                return nodeIds.stream().map(i -> new NodeStat(i, 1, 2, 0, Map.of("next", 1))).toList();
            }
        };
    }

    @Test
    void returnsItemsInOrderWithAsOf() {
        NodeStats s = new NodeStatsService(store(Optional.of(List.of("a", "b"))), CLOCK).stats("j", 2);
        assertThat(s.journeyId()).isEqualTo("j");
        assertThat(s.version()).isEqualTo(2);
        assertThat(s.asOf()).isEqualTo(CLOCK.instant());
        assertThat(s.items()).extracting(NodeStat::nodeId).containsExactly("a", "b");
    }

    @Test
    void noNodes_returnsEmptyItems() {
        assertThat(new NodeStatsService(store(Optional.of(List.of())), CLOCK).stats("j", 1).items()).isEmpty();
    }

    @Test
    void missingVersion_isNotFound() {
        assertThatThrownBy(() -> new NodeStatsService(store(Optional.empty()), CLOCK).stats("j", 1))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.JOURNEY_NOT_FOUND));
    }

    @Test
    void nonPositiveVersion_isValidationFailed() {
        assertThatThrownBy(() -> new NodeStatsService(store(Optional.empty()), CLOCK).stats("j", 0))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }
}
