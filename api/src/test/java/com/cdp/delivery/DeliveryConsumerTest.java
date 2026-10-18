package com.cdp.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import com.cdp.common.fault.FaultInjector;
import com.cdp.delivery.channel.ChannelAdapter;
import com.cdp.engine.EffectExecutor;

class DeliveryConsumerTest {

    private static final Duration DELAY = Duration.ofMillis(300);

    private final OutboxStore outbox = mock(OutboxStore.class);
    private final DeliveryTaskStore tasks = mock(DeliveryTaskStore.class);
    private final ChannelAdapter channel = mock(ChannelAdapter.class);
    private final TransactionTemplate tx = mock(TransactionTemplate.class);

    private DeliveryConsumer consumer() {
        return new DeliveryConsumer(
                outbox,
                tasks,
                mock(KillSwitch.class),
                mock(EffectExecutor.class),
                tx,
                mock(FaultInjector.class),
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC),
                channel,
                new DeliveryProperties(DELAY));
    }

    @Test
    void transientFailure_waitsRequeueDelay_thenRequeues() {
        org.mockito.Mockito.doThrow(new CannotCreateTransactionException("down"))
                .when(tx)
                .executeWithoutResult(any());
        long t0 = System.nanoTime();
        assertThatThrownBy(() -> consumer().onMessage("k"))
                .isInstanceOf(ImmediateRequeueAmqpException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isGreaterThanOrEqualTo(DELAY.minusMillis(20));
    }

    @Test
    void nonTransientFailure_deadLetters_withoutWaiting() {
        org.mockito.Mockito.doThrow(new DataIntegrityViolationException("bad"))
                .when(tx)
                .executeWithoutResult(any());
        long t0 = System.nanoTime();
        assertThatThrownBy(() -> consumer().onMessage("k"))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(DELAY);
    }

    @Test
    @SuppressWarnings("unchecked")
    void channelType_comesFromAdapter() {
        when(channel.channelType()).thenReturn("SMS");
        Instant t = Instant.parse("2026-01-01T00:00:00Z");
        when(outbox.find("k"))
                .thenReturn(
                        Optional.of(
                                new OutboxEntry(
                                        "k", "t", "e", "j", 1, "n", 1, 0, "SendMessage",
                                        Map.of(
                                                "template_id", "tpl",
                                                "channel_id", "ch",
                                                "variables", Map.of()),
                                        OutboxStatus.PENDING, t, 0, t, null)));
        ArgumentCaptor<DeliveryTask> captured = ArgumentCaptor.forClass(DeliveryTask.class);
        when(tasks.insertIfAbsentAndLock(captured.capture()))
                .thenAnswer(
                        inv -> {
                            DeliveryTask d = captured.getValue();
                            return new DeliveryTask(
                                    d.idempotencyKey(), d.tenantId(), d.enrollmentId(), d.journeyId(),
                                    d.journeyVersion(), d.nodeId(), d.visitSeq(), d.templateId(),
                                    d.channelId(), d.channelType(), null, DeliveryTaskStatus.SENT,
                                    null, null, t, t, t);
                        });
        org.mockito.Mockito.doAnswer(
                        inv -> {
                            ((Consumer<Object>) inv.getArgument(0)).accept(null);
                            return null;
                        })
                .when(tx)
                .executeWithoutResult(any());

        consumer().onMessage("k");

        assertThat(captured.getValue().channelType()).isEqualTo("SMS");
    }
}
