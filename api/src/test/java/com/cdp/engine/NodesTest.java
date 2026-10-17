package com.cdp.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.cdp.engine.model.TriggerSnapshot;
import com.cdp.engine.model.WakeCondition;
import com.cdp.engine.model.WakeSignal;
import com.cdp.engine.node.Effect;
import com.cdp.engine.node.NodeConfig;
import com.cdp.engine.node.NodeContext;
import com.cdp.engine.node.NodeResult;
import com.cdp.engine.node.ResultStatus;
import com.cdp.engine.node.builtin.SendMessageNode;
import com.cdp.engine.node.builtin.WaitDelayNode;
import com.cdp.engine.runtime.IdempotencyKeys;

class NodesTest {

    private static final Instant NOW = Instant.parse("2026-10-18T02:00:00Z");

    private static NodeContext ctx(String nodeId, Map<String, Object> config, WakeSignal signal) {
        TriggerSnapshot trigger =
                new TriggerSnapshot(
                        "e1",
                        "cart_added",
                        NOW,
                        "CUSTOMER_BEHAVIOR",
                        Map.of("email", "x@y.z"),
                        Map.of());
        return new NodeContext(
                "t",
                "enr",
                "p",
                nodeId,
                new NodeConfig(config),
                Map.of(),
                Map.of(),
                Map.of(),
                List.of(),
                trigger,
                Map.of(),
                NOW,
                signal,
                NOW);
    }

    @Test
    void waitDelay_waitsThenAdvancesOnTimerFired() {
        WaitDelayNode node = new WaitDelayNode();
        NodeResult first = node.execute(ctx("w", Map.of("duration", "PT1H"), null));
        assertThat(first.status()).isEqualTo(ResultStatus.WAIT);
        assertThat(first.wakeCondition())
                .isEqualTo(new WakeCondition.Timer(NOW.plusSeconds(3600)));
        assertThat(first.decisionReason()).contains("PT1H");

        NodeResult second =
                node.execute(ctx("w", Map.of("duration", "PT1H"), new WakeSignal.TimerFired(NOW)));
        assertThat(second.status()).isEqualTo(ResultStatus.ADVANCE);
        assertThat(second.outPort()).isEqualTo("next");
    }

    @Test
    void waitDelay_validatesDuration() {
        WaitDelayNode node = new WaitDelayNode();
        assertThat(node.validate(new NodeConfig(Map.of("duration", "PT1H")), null).errors())
                .isEmpty();
        assertThat(node.validate(new NodeConfig(Map.of("duration", "PT0S")), null).errors())
                .isNotEmpty();
        assertThat(node.validate(new NodeConfig(Map.of("duration", "x")), null).errors())
                .isNotEmpty();
        assertThat(node.validate(new NodeConfig(Map.of()), null).errors()).isNotEmpty();
    }

    @Test
    void sendMessage_returnsUnstampedEffect_andReasonHasNoPropertyValues() {
        NodeResult r =
                new SendMessageNode()
                        .execute(ctx("s", Map.of("template_id", "tpl", "channel_id", "ch"), null));
        assertThat(r.outPort()).isEqualTo("sent");
        assertThat(r.effects()).hasSize(1);
        assertThat(r.effects().get(0)).isInstanceOf(Effect.SendMessage.class);
        assertThat(r.effects().get(0).idempotencyKey()).isNull();
        assertThat(r.decisionReason()).doesNotContain("x@y.z");
    }

    @Test
    void idempotencyKey_format() {
        assertThat(IdempotencyKeys.of("enr", "send_1", 3, 0)).isEqualTo("enr:send_1:3:0");
    }
}
