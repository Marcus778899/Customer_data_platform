package com.cdp.delivery.channel;

import java.time.Clock;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** 寫入 mock_channel_message（ON CONFLICT DO NOTHING），與 consumer 同一交易；驗收以此表計數。 */
@Component
public class MockChannelAdapter implements ChannelAdapter {

    private final JdbcClient jdbc;
    private final Clock clock;

    public MockChannelAdapter(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public String channelType() {
        return "MOCK";
    }

    @Override
    public SendResult send(OutboundMessage message) {
        throw new UnsupportedOperationException("TODO(BE-02)");
    }
}
