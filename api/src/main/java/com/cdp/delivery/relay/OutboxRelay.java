package com.cdp.delivery.relay;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
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
                log.error("unknown effect_type {} for outbox {}; rescheduled", entry.effectType(), key);
                outbox.reschedule(key, clock.instant().plus(properties.retryDelay()));
                continue;
            }
            try {
                faults.at(FaultPoint.RELAY_BEFORE_PUBLISH, key);
            } catch (RuntimeException e) {
                log.warn("relay interrupted before publish for {}: {}", key, e.toString());
                break;
            }
            String failure = publish(routingKey, key);
            if (failure != null) {
                log.warn("publish failed for outbox {}; rescheduled: {}", key, failure);
                outbox.reschedule(key, clock.instant().plus(properties.retryDelay()));
                break; // 一批只等一次失敗（broker 不可用時不逐列等逾時）
            }
            try {
                faults.at(FaultPoint.RELAY_AFTER_CONFIRM, key);
            } catch (RuntimeException e) {
                log.warn("relay interrupted after confirm for {}: {}", key, e.toString());
                break;
            }
            outbox.markPublished(key, clock.instant());
            published++;
        }
        return published;
    }

    /** correlated confirm：回傳 null 表示 broker 已 ack 且未被 returned，否則為失敗原因。 */
    private String publish(String routingKey, String key) {
        CorrelationData correlation = new CorrelationData(key);
        try {
            rabbit.invoke(
                    ops -> {
                        ops.convertAndSend(RabbitTopology.EXCHANGE, routingKey, key, correlation);
                        return null;
                    });
            CorrelationData.Confirm confirm =
                    correlation.getFuture().get(CONFIRM_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            if (!confirm.isAck()) {
                return "nack: " + confirm.getReason();
            }
            if (correlation.getReturned() != null) {
                return "returned: " + correlation.getReturned().getReplyText();
            }
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return e.toString();
        } catch (Exception e) {
            return e.toString();
        }
    }

    private static String routingKey(String effectType) {
        return "SendMessage".equals(effectType) ? RabbitTopology.SEND_MESSAGE_ROUTING_KEY : null;
    }
}
