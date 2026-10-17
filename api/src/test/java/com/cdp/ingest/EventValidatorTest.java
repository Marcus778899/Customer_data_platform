package com.cdp.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.cdp.common.web.FieldError;

class EventValidatorTest {

    private static final Instant NOW = Instant.parse("2026-10-18T02:00:00Z");
    private static final String ULID = "01JCXYZ8K3M4N5P6Q7R8S9T0AB";
    private final EventValidator validator = new EventValidator();

    private static EventRequest valid() {
        return new EventRequest(
                ULID,
                SourceType.CUSTOMER_BEHAVIOR,
                null,
                "cart_item_added",
                NOW,
                new EventIdentity("p1", null),
                Map.of(),
                null);
    }

    private List<String> fields(EventRequest e) {
        return validator.validate(e, NOW).stream().map(FieldError::field).toList();
    }

    @Test
    void validEventPasses() {
        assertThat(validator.validate(valid(), NOW)).isEmpty();
    }

    @Test
    void reportsFieldPaths() {
        EventRequest bad =
                new EventRequest(
                        "nope",
                        SourceType.INTERNAL_OPERATION,
                        null,
                        "Bad-Name",
                        NOW.plusSeconds(600),
                        new EventIdentity(null, null),
                        null,
                        null);
        assertThat(fields(bad))
                .contains(
                        "event_id",
                        "source_id",
                        "name",
                        "occurred_at",
                        "identity.profile_id",
                        "context.actor_id");
    }

    @Test
    void customerBehaviorMustNotCarryActor() {
        EventRequest e =
                new EventRequest(
                        ULID,
                        SourceType.CUSTOMER_BEHAVIOR,
                        null,
                        "x",
                        NOW,
                        new EventIdentity("p", null),
                        null,
                        new EventContext("u", "r", null, null, null, null));
        assertThat(fields(e)).contains("context.actor_id", "context.actor_role");
    }
}
