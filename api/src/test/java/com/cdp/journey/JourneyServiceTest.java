package com.cdp.journey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.cdp.common.UlidGenerator;
import com.cdp.common.web.ApiException;
import com.cdp.common.web.ErrorCode;
import com.cdp.engine.node.builtin.EndNode;
import com.cdp.engine.node.builtin.EventSourceNode;
import com.cdp.engine.node.builtin.SendMessageNode;
import com.cdp.engine.node.builtin.StartNode;
import com.cdp.engine.node.builtin.WaitDelayNode;
import com.cdp.engine.runtime.NodeRegistry;

class JourneyServiceTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-18T02:00:00Z"), ZoneOffset.UTC);

    private final JourneyDefinitionStore store =
            new JourneyDefinitionStore() {
                @Override
                public JourneyDefinition insertNewVersion(JourneyDefinition d) {
                    return new JourneyDefinition(
                            d.journeyId(),
                            d.tenantId(),
                            1,
                            d.name(),
                            d.description(),
                            d.status(),
                            d.settings(),
                            d.entryNode(),
                            d.nodes(),
                            d.createdAt(),
                            d.publishedAt());
                }

                @Override
                public Optional<JourneyDefinition> find(String t, String j, int v) {
                    return Optional.empty();
                }

                @Override
                public List<EventSourceBinding> findActiveEventSources(String t, String e) {
                    return List.of();
                }
            };

    private final JourneyService service =
            new JourneyService(
                    store,
                    new NodeRegistry(
                            List.of(
                                    new StartNode(),
                                    new EventSourceNode(),
                                    new WaitDelayNode(),
                                    new SendMessageNode(),
                                    new EndNode())),
                    new UlidGenerator(clock),
                    clock);

    private static JourneyNodeDefinition n(
            String id, String type, Map<String, Object> config, Map<String, Object> outs) {
        return new JourneyNodeDefinition(id, type, null, config, outs);
    }

    private CreateJourneyRequest request(String waitTarget) {
        return new CreateJourneyRequest(
                null,
                "j",
                null,
                Map.of(),
                "s",
                List.of(
                        n("s", "StartNode", Map.of(), Map.of("next", "src")),
                        n("src", "EventSourceNode", Map.of("event_name", "e"), Map.of("next", "w")),
                        n(
                                "w",
                                "WaitDelayNode",
                                Map.of("duration", "PT1H"),
                                Map.of("next", waitTarget)),
                        n(
                                "m",
                                "SendMessageNode",
                                Map.of("template_id", "t", "channel_id", "c"),
                                Map.of("sent", "end")),
                        n("end", "EndNode", Map.of(), Map.of())));
    }

    @Test
    void validDefinitionIsActive() {
        var response = service.create("tenant", request("m"));
        assertThat(response.version()).isEqualTo(1);
        assertThat(response.status()).isEqualTo(JourneyStatus.ACTIVE);
        assertThat(response.journeyId()).hasSize(26);
    }

    @Test
    void cycleAndDanglingTargetsAreRejected() {
        assertThatThrownBy(() -> service.create("tenant", request("src")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> service.create("tenant", request("ghost")))
                .isInstanceOf(ApiException.class);
    }
}
