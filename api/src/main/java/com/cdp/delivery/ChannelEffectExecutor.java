package com.cdp.delivery;

import java.time.Clock;

import org.springframework.stereotype.Component;

import com.cdp.delivery.channel.ChannelAdapter;
import com.cdp.engine.EffectExecutor;
import com.cdp.engine.node.Effect;

/** SendMessage → ChannelAdapter → 更新 delivery_task 與 delivery_history；須在 consumer 交易內呼叫。 */
@Component
public class ChannelEffectExecutor implements EffectExecutor {

    private final ChannelAdapter channel;
    private final DeliveryTaskStore tasks;
    private final Clock clock;

    public ChannelEffectExecutor(ChannelAdapter channel, DeliveryTaskStore tasks, Clock clock) {
        this.channel = channel;
        this.tasks = tasks;
        this.clock = clock;
    }

    @Override
    public void execute(String tenantId, String enrollmentId, Effect effect) {
        throw new UnsupportedOperationException("TODO(BE-02)");
    }

    @Override
    public void cancelPending(String tenantId, String enrollmentId) {
        throw new UnsupportedOperationException("v0.3.0 (G10)");
    }

    @Override
    public void cancelPendingByJourney(String tenantId, String journeyId) {
        throw new UnsupportedOperationException("v0.3.0 (G10)");
    }
}
