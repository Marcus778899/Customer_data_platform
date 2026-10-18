package com.cdp.delivery;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Component;

import com.cdp.delivery.channel.ChannelAdapter;
import com.cdp.delivery.channel.OutboundMessage;
import com.cdp.delivery.channel.SendResult;
import com.cdp.engine.EffectExecutor;
import com.cdp.engine.model.DeliveryRecord;
import com.cdp.engine.model.DeliveryStatus;
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
        if (!(effect instanceof Effect.SendMessage send)) {
            throw new IllegalArgumentException("unsupported effect: " + effect);
        }
        DeliveryTask task =
                tasks.find(send.idempotencyKey())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "delivery_task missing: " + send.idempotencyKey()));
        SendResult result =
                channel.send(
                        new OutboundMessage(
                                send.idempotencyKey(),
                                tenantId,
                                enrollmentId,
                                send.templateId(),
                                send.channelId(),
                                send.variables()));
        Instant now = clock.instant();
        tasks.update(
                new DeliveryTask(
                        task.idempotencyKey(),
                        task.tenantId(),
                        task.enrollmentId(),
                        task.journeyId(),
                        task.journeyVersion(),
                        task.nodeId(),
                        task.visitSeq(),
                        send.templateId(),
                        send.channelId(),
                        channel.channelType(),
                        task.messageType(),
                        DeliveryTaskStatus.SENT,
                        null,
                        result.deliveryId(),
                        now,
                        task.createdAt(),
                        now));
        tasks.appendDeliveryHistory(
                tenantId,
                enrollmentId,
                new DeliveryRecord(
                        task.nodeId(),
                        task.visitSeq(),
                        send.templateId(),
                        channel.channelType(),
                        send.channelId(),
                        task.messageType(),
                        DeliveryStatus.SENT,
                        null,
                        now,
                        result.deliveryId()));
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
