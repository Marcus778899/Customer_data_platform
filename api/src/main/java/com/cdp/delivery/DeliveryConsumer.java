package com.cdp.delivery;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import com.cdp.common.fault.FaultInjector;
import com.cdp.common.fault.FaultPoint;
import com.cdp.engine.EffectExecutor;
import com.cdp.engine.node.Effect;

/**
 * 冪等 consumer（INIT-01 紅線 3）。單一交易：insertIfAbsentAndLock → 已 SENT/CANCELLED 即結束 → KillSwitch →
 * EffectExecutor → 提交 → FaultPoint.CONSUMER_AFTER_COMMIT → ack。無法解析 → DLQ；DB 不可用 →
 * ImmediateRequeueAmqpException。
 */
@Component
public class DeliveryConsumer {

    private static final Logger log = LoggerFactory.getLogger(DeliveryConsumer.class);

    private final OutboxStore outbox;
    private final DeliveryTaskStore tasks;
    private final KillSwitch killSwitch;
    private final EffectExecutor executor;
    private final TransactionTemplate tx;
    private final FaultInjector faults;
    private final Clock clock;

    public DeliveryConsumer(
            OutboxStore outbox,
            DeliveryTaskStore tasks,
            KillSwitch killSwitch,
            EffectExecutor executor,
            TransactionTemplate tx,
            FaultInjector faults,
            Clock clock) {
        this.outbox = outbox;
        this.tasks = tasks;
        this.killSwitch = killSwitch;
        this.executor = executor;
        this.tx = tx;
        this.faults = faults;
        this.clock = clock;
    }

    @RabbitListener(
            queues = RabbitTopology.SEND_MESSAGE_QUEUE,
            autoStartup = "${spring.rabbitmq.listener.simple.auto-startup:true}")
    public void onMessage(String idempotencyKey) {
        try {
            tx.executeWithoutResult(status -> handle(idempotencyKey));
        } catch (TransientDataAccessException
                | RecoverableDataAccessException
                | DataAccessResourceFailureException
                | CannotCreateTransactionException e) {
            log.warn("transient DB failure for {}; requeue: {}", idempotencyKey, e.toString());
            throw new ImmediateRequeueAmqpException("transient DB failure", e);
        } catch (DataAccessException e) {
            log.error("non-transient DB failure for {}; dead-letter", idempotencyKey, e);
            throw new AmqpRejectAndDontRequeueException("non-transient DB failure", e);
        }
        faults.at(FaultPoint.CONSUMER_AFTER_COMMIT, idempotencyKey);
    }

    private void handle(String key) {
        OutboxEntry entry =
                outbox.find(key)
                        .orElseThrow(
                                () ->
                                        new AmqpRejectAndDontRequeueException(
                                                "no outbox row for " + key));
        Effect.SendMessage effect = parse(entry);

        Instant now = clock.instant();
        DeliveryTask task =
                tasks.insertIfAbsentAndLock(
                        new DeliveryTask(
                                key,
                                entry.tenantId(),
                                entry.enrollmentId(),
                                entry.journeyId(),
                                entry.journeyVersion(),
                                entry.nodeId(),
                                entry.visitSeq(),
                                effect.templateId(),
                                effect.channelId(),
                                "MOCK",
                                null,
                                DeliveryTaskStatus.PENDING,
                                null,
                                null,
                                now,
                                now,
                                now));
        if (task.status() != DeliveryTaskStatus.PENDING) {
            return; // 重複投遞：已處理過
        }
        if (killSwitch.isStopped(entry.tenantId(), entry.journeyId(), entry.journeyVersion())) {
            tasks.update(
                    new DeliveryTask(
                            task.idempotencyKey(),
                            task.tenantId(),
                            task.enrollmentId(),
                            task.journeyId(),
                            task.journeyVersion(),
                            task.nodeId(),
                            task.visitSeq(),
                            task.templateId(),
                            task.channelId(),
                            task.channelType(),
                            task.messageType(),
                            DeliveryTaskStatus.CANCELLED,
                            null,
                            null,
                            task.attemptedAt(),
                            task.createdAt(),
                            now));
            return;
        }
        executor.execute(entry.tenantId(), entry.enrollmentId(), effect);
    }

    @SuppressWarnings("unchecked")
    private static Effect.SendMessage parse(OutboxEntry entry) {
        if (!"SendMessage".equals(entry.effectType())) {
            throw new AmqpRejectAndDontRequeueException("unsupported effect_type " + entry.effectType());
        }
        Map<String, Object> p = entry.payload();
        if (p == null
                || !(p.get("template_id") instanceof String template)
                || !(p.get("channel_id") instanceof String channel)
                || !(p.get("variables") instanceof Map<?, ?> variables)) {
            throw new AmqpRejectAndDontRequeueException(
                    "unparsable payload for " + entry.idempotencyKey());
        }
        return new Effect.SendMessage(
                entry.idempotencyKey(), template, channel, (Map<String, Object>) variables);
    }
}
