package com.cdp.delivery.relay;

import java.time.Clock;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.cdp.common.fault.FaultInjector;
import com.cdp.common.fault.FaultPoint;
import com.cdp.delivery.OutboxEntry;
import com.cdp.delivery.OutboxStore;
import com.cdp.delivery.RabbitTopology;

/**
 * 全系統唯一可依賴 RabbitTemplate 的類別（ArchitectureTest）。每列：發佈 body = idempotency_key → 等
 * publisher confirm → markPublished；confirm 失敗則留 PENDING 下輪再推。
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final long CONFIRM_TIMEOUT_MILLIS = 5000;

    private final OutboxStore outbox;
    private final RabbitTemplate rabbit;
    private final RelayProperties properties;
    private final FaultInjector faults;
    private final Clock clock;
    private final TransactionTemplate tx;

    public OutboxRelay(
            OutboxStore outbox,
            RabbitTemplate rabbit,
            RelayProperties properties,
            FaultInjector faults,
            Clock clock,
            TransactionTemplate tx) {
        this.outbox = outbox;
        this.rabbit = rabbit;
        this.properties = properties;
        this.faults = faults;
        this.clock = clock;
        this.tx = tx;
    }

    @Scheduled(fixedDelayString = "${cdp.relay.poll-interval}")
    public void tick() {
        runOnce();
    }

    /** 推送一批，回傳筆數（測試可直接呼叫）。 */
    public int runOnce() {
        Integer n = tx.execute(status -> publishBatch());
        return n == null ? 0 : n;
    }

    private int publishBatch() {
        List<OutboxEntry> due = outbox.lockDue(clock.instant(), properties.batchSize());
        int published = 0;
        for (OutboxEntry entry : due) {
            String key = entry.idempotencyKey();
            String routingKey = routingKey(entry.effectType());
            if (routingKey == null) {
                log.error("unknown effect_type {} for outbox {}; left PENDING", entry.effectType(), key);
                continue;
            }
            try {
                faults.at(FaultPoint.RELAY_BEFORE_PUBLISH, key);
                rabbit.invoke(
                        ops -> {
                            ops.convertAndSend(RabbitTopology.EXCHANGE, routingKey, key);
                            ops.waitForConfirmsOrDie(CONFIRM_TIMEOUT_MILLIS);
                            return null;
                        });
                faults.at(FaultPoint.RELAY_AFTER_CONFIRM, key);
            } catch (RuntimeException e) {
                log.warn("publish failed for outbox {}; left PENDING: {}", key, e.toString());
                continue;
            }
            outbox.markPublished(key, clock.instant());
            published++;
        }
        return published;
    }

    private static String routingKey(String effectType) {
        return "SendMessage".equals(effectType) ? RabbitTopology.SEND_MESSAGE_ROUTING_KEY : null;
    }
}
