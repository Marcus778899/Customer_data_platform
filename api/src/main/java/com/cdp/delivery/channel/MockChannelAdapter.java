package com.cdp.delivery.channel;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/** 寫入 mock_channel_message（ON CONFLICT DO NOTHING），與 consumer 同一交易；驗收以此表計數。 */
@Component
public class MockChannelAdapter implements ChannelAdapter {

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Clock clock;

    public MockChannelAdapter(JdbcClient jdbc, JsonMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public String channelType() {
        return "MOCK";
    }

    @Override
    public SendResult send(OutboundMessage message) {
        Map<String, Object> variables =
                message.variables() == null ? Map.of() : message.variables();
        jdbc.sql(
                        "insert into mock_channel_message (idempotency_key, enrollment_id,"
                                + " template_id, channel_id, variables, sent_at) values (:key,"
                                + " :enrollment, :template, :channel, cast(:variables as jsonb),"
                                + " :sentAt) on conflict (idempotency_key) do nothing")
                .param("key", message.idempotencyKey())
                .param("enrollment", message.enrollmentId())
                .param("template", message.templateId())
                .param("channel", message.channelId())
                .param("variables", json.writeValueAsString(variables))
                .param("sentAt", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .update();
        return new SendResult("mock-" + message.idempotencyKey());
    }
}
