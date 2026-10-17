package com.cdp.delivery;

import java.time.Clock;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.cdp.common.fault.FaultInjector;
import com.cdp.engine.EffectExecutor;

/**
 * 冪等 consumer（INIT-01 紅線 3）。單一交易：insertIfAbsentAndLock → 已 SENT/CANCELLED 即結束 → KillSwitch →
 * EffectExecutor → 提交 → FaultPoint.CONSUMER_AFTER_COMMIT → ack。無法解析 → DLQ；DB 不可用 →
 * ImmediateRequeueAmqpException。
 */
@Component
public class DeliveryConsumer {

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

    @RabbitListener(queues = RabbitTopology.SEND_MESSAGE_QUEUE)
    public void onMessage(String idempotencyKey) {
        throw new UnsupportedOperationException("TODO(BE-02)");
    }
}
