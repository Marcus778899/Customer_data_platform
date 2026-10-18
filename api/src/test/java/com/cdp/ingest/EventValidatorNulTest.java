package com.cdp.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.cdp.common.web.FieldError;

class EventValidatorNulTest {

    private static final Instant NOW = Instant.parse("2026-10-18T02:00:00Z");
    private static final String NUL = String.valueOf((char) 0);
    private final EventValidator validator = new EventValidator();

    private List<String> fields(Map<String, Object> props, String sourceId) {
        EventRequest e =
                new EventRequest(
                        "01JCXYZ8K3M4N5P6Q7R8S9T0AB",
                        SourceType.CUSTOMER_BEHAVIOR,
                        sourceId,
                        "cart_item_added",
                        NOW,
                        new EventIdentity("p1", null),
                        props,
                        null);
        return validator.validate(e, NOW).stream().map(FieldError::field).toList();
    }

    @Test
    void nestedValueKeyAndSourceIdAreReported() {
        assertThat(fields(Map.of("a", Map.of("b", List.of("ok", "x" + NUL))), null))
                .containsExactly("properties.a.b[1]");
        assertThat(fields(Map.of("k" + NUL, "v"), null)).hasSize(1);
        assertThat(fields(Map.of(), "web" + NUL)).containsExactly("source_id");
    }

    @Test
    void cleanStringsPass() {
        assertThat(fields(Map.of("a", "b"), "web")).isEmpty();
    }
}
