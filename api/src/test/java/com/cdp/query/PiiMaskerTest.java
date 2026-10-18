package com.cdp.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.cdp.engine.model.EnrollmentStatus;
import com.cdp.engine.model.TriggerSnapshot;

class PiiMaskerTest {

    private final PiiMasker masker = new PiiMasker(new MaskingProperties(Set.of("keep")));

    private EnrollmentDetail detail(Map<String, Object> props, Map<String, Object> evaluated) {
        Instant t = Instant.parse("2026-10-18T02:00:00Z");
        return new EnrollmentDetail(
                "e", "j", 1, "n", "p", EnrollmentStatus.WAITING, "w", null, "WaitDelayNode", t, t, null, null,
                null, Map.of("v", 1), new TriggerSnapshot("id", "ev", t, "CUSTOMER_BEHAVIOR", props, Map.of()),
                List.of(new TraceEntry(1, "w", "WaitDelayNode", null, t, null, null, "r", evaluated)),
                List.of(), null, t, List.of());
    }

    @Test
    void masksLeavesKeepsKeysAndHonoursWhitelist() {
        EnrollmentDetail out =
                masker.mask(
                        detail(
                                Map.of("email", "alice@example.com", "keep", Map.of("x", "y"), "n", 5),
                                Map.of("duration", "P3D", "phone", "1")));
        assertThat(out.triggerSnapshot().properties())
                .containsEntry("email", "a***@example.com")
                .containsEntry("keep", Map.of("x", "y"))
                .containsEntry("n", "***");
        assertThat(out.trace().get(0).evaluated()).containsEntry("duration", "P3D").containsEntry("phone", "***");
        assertThat(out.variables()).containsEntry("v", "***");
        assertThat(out.maskedPaths())
                .containsExactlyInAnyOrder(
                        "variables.v", "trigger_snapshot.properties.email", "trigger_snapshot.properties.n",
                        "trace[0].evaluated.phone");
    }
}
